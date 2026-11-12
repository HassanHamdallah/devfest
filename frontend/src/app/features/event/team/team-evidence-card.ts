import { ChangeDetectionStrategy, Component, input } from "@angular/core";
import { RoomEvidence } from "../../../core/event/event-models";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { EventMaskedClue } from "./masked-clue";

/**
 * The evidence part of a finished room's card on the team home (inside event-team-room-card): the team's latest technical output and clue answer with its result, and the
 * room's official clue behind the eye toggle when the team collected it. A room with an activity (AI mission) shows
 * only its outcome: "Clue unlocked" with the clue, or that the required score was not reached; the Problem Solving
 * contest shows only whether its clue was unlocked.
 */
@Component({
  selector: "event-team-evidence-card",
  imports: [EventMaskedClue, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    "[attr.data-evidence]": "evidence().roomCode",
  },
  styles: `
    :host {
      display: block;
    }
    dl {
      display: grid;
      gap: var(--space-2);
      margin: 0;
    }
    dt {
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-semibold);
      color: var(--color-text-muted);
    }
    dd {
      margin: 0;
    }
    .output {
      margin: 0;
      padding: var(--space-2) var(--space-3);
      white-space: pre-wrap;
      overflow-wrap: anywhere;
      max-block-size: 12rem;
      overflow: auto;
      background: var(--color-surface-subtle);
      border-radius: var(--radius-sm);
    }
    .answer {
      overflow-wrap: anywhere;
    }
  `,
  template: `<dl>
      @if (evidence().activity === "LINUX_TERMINAL") {
        <dt>Your answers</dt>
        <dd data-form-outcome>
          @if (evidence().resultRecorded) {
            <ui-status-chip variant="success">Submitted</ui-status-chip>
          } @else {
            <span class="event-muted">Not submitted</span>
          }
        </dd>
      } @else if (evidence().activity === "CONTEST_RESULTS") {
        <dt>Problem Solving contest</dt>
        <dd data-contest-outcome>
          @if (evidence().clueValue) {
            <ui-status-chip variant="success">Clue unlocked</ui-status-chip>
          } @else {
            <span class="event-muted">No clue unlocked for this room</span>
          }
        </dd>
      } @else if (evidence().activity) {
        <dt>AI mission</dt>
        <dd data-quiz-outcome>
          @if (evidence().clueValue) {
            <ui-status-chip variant="success">Clue unlocked</ui-status-chip>
          } @else if (evidence().resultRecorded) {
            You did not reach the required score for this room's clue.
          } @else {
            <span class="event-muted">No result recorded</span>
          }
        </dd>
      } @else if (evidence().technicalOutput === null && evidence().clueAnswer === null) {
        <dt>Your answer</dt>
        <dd class="event-muted">No answer submitted</dd>
      } @else {
        <dt>Your technical output</dt>
        <dd><p class="output">{{ evidence().technicalOutput }}</p></dd>
        <dt>Your clue answer</dt>
        <dd class="event-inline">
          <span class="answer">{{ evidence().clueAnswer }}</span>
          <ui-status-chip [variant]="evidence().clueCorrect ? 'success' : 'danger'">{{
            evidence().clueCorrect ? "Correct" : "Incorrect"
          }}</ui-status-chip>
        </dd>
      }
      <dt>Room clue</dt>
      <dd>
        @if (evidence().clueValue; as clue) {
          <event-masked-clue [value]="clue" />
        } @else {
          <ui-status-chip variant="neutral">Clue not collected</ui-status-chip>
          @if (recoverable()) {
            <p class="event-muted">You may recover it in the final.</p>
          }
        }
      </dd>
    </dl>`,
})
export class EventTeamEvidenceCard {
  readonly evidence = input.required<RoomEvidence>();
  /** True when this is the one missing clue a team with 3 clues can still win back in the final. */
  readonly recoverable = input(false);
}
