import { Injectable, effect, inject, signal, untracked } from "@angular/core";
import { EventApi } from "./event-api";

export const REVISION_POLL_INTERVAL_MS = 10_000;

/**
 * The only event poller (D14): GET /event/revision every 10 s while the tab is visible.
 * Pages refetch their one board endpoint when `revision()` changes; they never run their own timers.
 */
@Injectable({ providedIn: "root" })
export class RevisionPoller {
  private readonly eventApi = inject(EventApi);
  private readonly latest = signal<number | null>(null);
  private timer: ReturnType<typeof setInterval> | null = null;
  private running = false;
  private inFlight = false;

  /** Latest known event revision; null until the first successful poll after start(). */
  readonly revision = this.latest.asReadonly();

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
    this.pause();
    this.latest.set(null);
  }

  private readonly handleVisibility = () => {
    if (document.visibilityState === "visible") this.resume();
    else this.pause();
  };

  private resume(): void {
    if (document.visibilityState !== "visible" || this.timer !== null) return;
    void this.poll();
    this.timer = setInterval(() => void this.poll(), REVISION_POLL_INTERVAL_MS);
  }

  private pause(): void {
    if (this.timer === null) return;
    clearInterval(this.timer);
    this.timer = null;
  }

  private async poll(): Promise<void> {
    if (this.inFlight || !this.running) return;
    this.inFlight = true;
    try {
      const { revision } = await this.eventApi.revision();
      if (this.running) this.latest.set(revision);
    } catch {
      // A failed poll is retried on the next tick; pages keep showing their last data.
    } finally {
      this.inFlight = false;
    }
  }
}

/**
 * Calls `reload` every time the polled revision changes after the first value this page sees.
 * The first value is the baseline because the page just loaded its own data. Call it in an
 * injection context (a field initializer or constructor); the effect ends with the component.
 */
export function reloadOnRevisionChange(reload: () => void): void {
  const poller = inject(RevisionPoller);
  let baseline = untracked(poller.revision);
  effect(() => {
    const current = poller.revision();
    if (current === null || current === baseline) return;
    const isFirstValue = baseline === null;
    baseline = current;
    if (!isFirstValue) untracked(reload);
  });
}
