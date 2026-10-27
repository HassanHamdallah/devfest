import { Injectable, effect, inject, signal, untracked } from "@angular/core";
import { Api } from "../api";
import { EventApi } from "./event-api";

/** GET /revision interval while the push stream is not open. */
export const FALLBACK_POLL_MS = 3_000;
/** GET /revision interval while the stream is open: a safety net in case a push is lost. */
export const SAFETY_POLL_MS = 30_000;
/** Wait before each reconnect after consecutive failures; the last value repeats. */
export const RECONNECT_DELAYS_MS: readonly number[] = [1_000, 2_000, 5_000, 10_000];

/**
 * How every event page learns that something changed (D14). The server pushes each committed revision over
 * Server-Sent Events (GET /event/stream, opened with a single-use ticket from POST /event/stream-ticket);
 * GET /event/revision catches up on every (re)connect and is the fallback while the stream is down. Pages
 * refetch their one board endpoint when `revision()` changes; they never run their own timers.
 */
@Injectable({ providedIn: "root" })
export class RevisionSync {
  private readonly api = inject(Api);
  private readonly eventApi = inject(EventApi);
  private readonly latest = signal<number | null>(null);
  private readonly streamOpen = signal(false);
  private running = false;
  /** Changes on stop and on session end, so callbacks of an older connection are ignored. */
  private generation = 0;
  private source: EventSource | null = null;
  private pollTimer: ReturnType<typeof setInterval> | null = null;
  private pollInterval: number | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private failures = 0;
  private inFlight = false;
  /** Counts pushes, so a poll answered before a newer push cannot move the value back. */
  private pushes = 0;

  /** Latest known event revision; null until the first poll or push after start(). */
  readonly revision = this.latest.asReadonly();
  /** True while the push stream is open. */
  readonly live = this.streamOpen.asReadonly();

  start(): void {
    if (this.running) return;
    this.running = true;
    document.addEventListener("visibilitychange", this.handleVisibility);
    this.resume();
  }

  stop(): void {
    if (!this.running) return;
    this.running = false;
    document.removeEventListener("visibilitychange", this.handleVisibility);
    this.halt();
    this.latest.set(null);
  }

  /** The stream stays open while the tab is hidden; polls wait, and one runs as soon as it is visible again. */
  private readonly handleVisibility = () => {
    if (document.visibilityState === "visible") void this.poll();
  };

  private resume(): void {
    this.setPolling(FALLBACK_POLL_MS);
    void this.poll();
    void this.connect(this.generation);
  }

  /** Closes the stream and clears every timer. */
  private halt(): void {
    this.generation++;
    this.closeSource();
    this.setPolling(null);
    if (this.reconnectTimer !== null) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.failures = 0;
  }

  private isCurrent(generation: number): boolean {
    return this.running && generation === this.generation;
  }

  private async connect(generation: number): Promise<void> {
    this.reconnectTimer = null;
    let ticket: string;
    try {
      ({ ticket } = await this.eventApi.streamTicket());
    } catch {
      if (this.isCurrent(generation)) this.scheduleReconnect(generation);
      return;
    }
    if (!this.isCurrent(generation)) return;
    const source = new EventSource(this.eventApi.streamUrl(ticket));
    this.source = source;
    source.onopen = () => {
      if (this.source !== source) return;
      this.failures = 0;
      this.streamOpen.set(true);
      this.setPolling(SAFETY_POLL_MS);
      // Catch up on anything committed while there was no stream.
      void this.poll();
    };
    source.addEventListener("revision", (event) => {
      if (this.source === source) this.pushed(event as MessageEvent<string>);
    });
    source.addEventListener("session-ended", () => {
      if (this.source === source) this.sessionEnded();
    });
    source.onerror = () => {
      // The browser would retry with the used ticket (401); reconnect with a fresh one and poll meanwhile.
      if (this.source !== source) return;
      this.closeSource();
      this.setPolling(FALLBACK_POLL_MS);
      this.scheduleReconnect(generation);
    };
  }

  private scheduleReconnect(generation: number): void {
    const delay =
      RECONNECT_DELAYS_MS[Math.min(this.failures, RECONNECT_DELAYS_MS.length - 1)];
    this.failures++;
    this.reconnectTimer = setTimeout(() => void this.connect(generation), delay);
  }

  private pushed(event: MessageEvent<string>): void {
    let revision: unknown;
    try {
      revision = JSON.parse(event.data)?.revision;
    } catch {
      return;
    }
    if (typeof revision !== "number") return;
    this.pushes++;
    const current = this.latest();
    if (current === null || revision > current) this.latest.set(revision);
  }

  /**
   * The server closed the stream because this session can no longer be used. GET /revision then answers 401 with
   * the reason, and the shared sign-out handling (api.ts) explains it on the login page; stop() follows from there.
   */
  private sessionEnded(): void {
    this.halt();
    const generation = this.generation;
    // The sign-out handling clears the user at once (stop() follows); if the user is still signed in (a token
    // renewal raced the check, or only the network failed), carry on.
    const carryOn = () => {
      if (this.isCurrent(generation) && this.api.user() !== null) this.resume();
    };
    this.eventApi.revision().then(carryOn, carryOn);
  }

  private async poll(): Promise<void> {
    if (this.inFlight || !this.running || document.visibilityState !== "visible") return;
    this.inFlight = true;
    const generation = this.generation;
    const pushes = this.pushes;
    try {
      const { revision } = await this.eventApi.revision();
      const current = this.latest();
      if (
        this.isCurrent(generation) &&
        revision !== current &&
        (pushes === this.pushes || current === null || revision > current)
      )
        this.latest.set(revision);
    } catch {
      // A failed poll is retried on the next tick; pages keep showing their last data.
    } finally {
      this.inFlight = false;
    }
  }

  private setPolling(interval: number | null): void {
    if (interval === this.pollInterval) return;
    if (this.pollTimer !== null) clearInterval(this.pollTimer);
    this.pollTimer = interval === null ? null : setInterval(() => void this.poll(), interval);
    this.pollInterval = interval;
  }

  private closeSource(): void {
    this.source?.close();
    this.source = null;
    this.streamOpen.set(false);
  }
}

/**
 * Calls `reload` every time the revision changes after the first value this page sees. The first value is the
 * baseline because the page just loaded its own data. Call it in an injection context (a field initializer or
 * constructor); the effect ends with the component.
 */
export function reloadOnRevisionChange(reload: () => void): void {
  const sync = inject(RevisionSync);
  let baseline = untracked(sync.revision);
  effect(() => {
    const current = sync.revision();
    if (current === null || current === baseline) return;
    const isFirstValue = baseline === null;
    baseline = current;
    if (!isFirstValue) untracked(reload);
  });
}
