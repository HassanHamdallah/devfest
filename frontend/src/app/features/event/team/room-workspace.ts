import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  output,
  signal,
  untracked,
  viewChild,
} from "@angular/core";
import { effectivePhase, remainingSeconds } from "../../../core/event/countdown";
import { EventApi } from "../../../core/event/event-api";
import { TeamBoard } from "../../../core/event/event-models";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { EventLinuxTerminal } from "../terminal/linux-terminal";
import { EventAiRoomActivity } from "./ai-room/ai-room-activity";
import { EventContestRoomActivity } from "./contest-room/contest-room-activity";
import { EventRoomSubmissionPanel } from "./room-submission-panel";
import { EventTerminalForm } from "./terminal-form";

/** The split view needs room for an 80-column terminal next to the answer form. */
const WIDE_QUERY = "(min-width: 900px)";

/** The terminal of one room in one round; it stays on screen ("Time is up") after its round ends. */
interface TerminalSession {
  roomId: number;
  round: number;
}

/**
 * The answer form of the team's current room, and for a room with the terminal enabled the in-browser Linux
 * terminal next to it (laptops) or on request below it (phones). The terminal code is loaded only on "Start".
 * A room with the AI quiz shows only the activity (its own lazy chunk, loaded only for that room): it has no answer
 * form, because the server records the team's result itself. The Problem Solving contest room shows its own panel
 * (the contest link and the released clue) the same way.
 */
@Component({
  selector: "event-team-room-workspace",
  imports: [EventAiRoomActivity, EventContestRoomActivity, EventLinuxTerminal, EventRoomSubmissionPanel, EventTerminalForm, UiButton, UiCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./room-workspace.css",
  templateUrl: "./room-workspace.html",
})
export class EventTeamRoomWorkspace {
  private readonly eventApi = inject(EventApi);

  readonly board = input.required<TeamBoard>();
  /** A submission got a response: the page reloads its board and receipts. */
  readonly changed = output<void>();

  private readonly now = signal(Date.now());
  private readonly receivedAt = linkedSignal({ source: this.board, computation: () => Date.now() });
  private readonly phase = computed(() => {
    const clock = this.board().clock;
    return effectivePhase(clock, remainingSeconds(clock, this.receivedAt(), this.now()));
  });
  /** This round's terminal room while the server would still hand out its files (rules §18). */
  private readonly eligible = computed<TerminalSession | null>(() => {
    const board = this.board();
    const room = board.scheduledRoom;
    const phase = this.phase();
    if (!room?.terminalEnabled || board.disqualified || phase === null || phase === "TIME_UP") return null;
    return { roomId: room.roomId, round: board.clock.currentRound };
  });

  /** The room's six-field form replaces the answer form when its activity is the Linux terminal (rules §18.7). */
  protected readonly formRoomId = computed(() => {
    const room = this.board().scheduledRoom;
    return room?.activity === "LINUX_TERMINAL" ? room.roomId : null;
  });
  /** The team's round is running on this page's clock (the form accepts answers only then). */
  protected readonly running = computed(() => this.phase() === "RUNNING");
  private readonly panel = viewChild(EventRoomSubmissionPanel);

  protected readonly wide = signal(matchMedia(WIDE_QUERY).matches);
  /** A phone user asked for the terminal anyway. */
  protected readonly opened = signal(false);
  protected readonly session = signal<TerminalSession | null>(null);
  protected readonly ended = computed(() => {
    const session = this.session();
    const eligible = this.eligible();
    return session !== null && (eligible?.roomId !== session.roomId || eligible.round !== session.round);
  });
  protected readonly showTerminal = computed(() => this.eligible() !== null || this.session() !== null);
  protected readonly preparing = signal(false);
  protected readonly loadFiles = () => this.eventApi.teamTerminalFiles(this.session()?.roomId ?? 0);
  protected readonly loadBundle = () => this.eventApi.terminalBundle(this.session()?.roomId ?? 0, "team");
  private assets: Promise<typeof import("../terminal/terminal-assets")> | null = null;

  constructor() {
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    const media = matchMedia(WIDE_QUERY);
    const onMedia = (event: MediaQueryListEvent) => this.wide.set(event.matches);
    media.addEventListener("change", onMedia);
    // While the team waits for the timer, the emulator and Linux image download in the background.
    effect(() => {
      const waiting = this.eligible() !== null && this.phase() === "ASSIGNED";
      if (waiting && (this.wide() || this.opened())) untracked(() => this.preload());
    });
    inject(DestroyRef).onDestroy(() => {
      clearInterval(timer);
      media.removeEventListener("change", onMedia);
      // Leaving the page frees the downloaded image; the terminal component destroys its VM itself.
      void this.assets?.then((module) => module.releaseTerminal());
    });
  }

  /** "Use selection as technical output": only rooms with the plain answer form offer it. */
  protected useOutput(text: string): void {
    this.panel()?.useTechnicalOutput(text);
  }

  protected start(): void {
    const eligible = this.eligible();
    if (!eligible) return;
    // The same module the terminal uses; kept so leaving the page can release the downloaded image.
    this.assets ??= import("../terminal/terminal-assets");
    this.session.set(eligible);
  }

  /** After "Time is up": removes the old terminal (and frees its memory). */
  protected close(): void {
    this.session.set(null);
  }

  private preload(): void {
    if (this.assets) return;
    this.preparing.set(true);
    this.assets = import("../terminal/terminal-assets");
    void this.assets.then((module) => module.preloadTerminal());
  }
}
