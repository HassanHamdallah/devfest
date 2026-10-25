import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from "@angular/core";
import { RoundPhase, TeamBoard } from "../../../core/event/event-models";
import { UiCountdown } from "../../../shared/ui/ui-countdown/ui-countdown";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";

const PHASE_TEXT: Record<RoundPhase, string> = {
  ASSIGNED: "Timer not started",
  RUNNING: "Round running",
  PAUSED: "Paused",
  TIME_UP: "Time's up",
};

/**
 * Top of the team home: the team name, where to be right now as a big card in the room's colour ("Go to the AI
 * room" before the timer starts, "You are in the AI room" after), the countdown, and the clues collected as four
 * capsules in the room colours, each labelled with its room.
 */
@Component({
  selector: "event-team-hero",
  imports: [UiCountdown, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: "./team-hero.html",
  styleUrl: "./team-hero.css",
})
export class EventTeamHero {
  readonly board = input.required<TeamBoard>();
  /** "Current room: AI (round 1 of 4)", or why there is none. */
  readonly roomLine = input.required<string>();
  /** "1 of 4 clues collected" */
  readonly cluesLine = input.required<string>();

  protected readonly room = computed(() => {
    const board = this.board();
    const round = board.clock.currentRound;
    return round >= 1 && round <= 4 ? board.scheduledRoom : null;
  });
  protected readonly toneClass = computed(() => {
    const room = this.room();
    return room ? `room-tone--${room.code}` : "where--none";
  });
  protected readonly title = computed(() => {
    const room = this.room();
    if (!room) return null;
    return this.board().clock.phase === "ASSIGNED"
      ? `Go to the ${room.name} room`
      : `You are in the ${room.name} room`;
  });
  protected readonly phaseText = computed(() => {
    const phase = this.board().clock.phase;
    return phase ? PHASE_TEXT[phase] : null;
  });
  protected readonly clues = computed(() =>
    [...this.board().rooms].sort((a, b) => a.position - b.position),
  );
}
