import { ChangeDetectionStrategy, Component, input } from "@angular/core";
import { RoomEvidence } from "../../../core/event/event-models";
import { UiRoomChip } from "../../../shared/ui/ui-room-chip/ui-room-chip";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { EventMaskedClue } from "./masked-clue";

/**
 * A finished room on the team home: the team's latest technical output and clue answer with its result, and the
 * room's official clue behind the eye toggle when the team collected it. A room with an activity (AI quiz) shows
 * only its outcome: "Clue unlocked" with the clue, or that the required score was not reached.
 */
@Component({
  selector: "event-team-evidence-card",
  imports: [EventMaskedClue, UiRoomChip, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    "[attr.data-evidence]": "evidence().roomCode",
    "[class]": "'room-tone--' + evidence().roomCode",
  },
  styles: `
    :host {
      display: grid;
      align-content: start;
      gap: var(--space-3);
      padding: var(--space-4);
      background: var(--color-surface);
      border: 1px solid var(--color-border);
      border-block-start: 6px solid var(--room-color);
      border-radius: var(--radius-card);
    }
    h3 {
      margin: 0;
      font-size: var(--font-size-base);
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
  template: `<div class="event-inline">
      <ui-room-chip [code]="evidence().roomCode" [name]="evidence().roomName" />
      <h3>Evidence</h3>
    </div>
    <dl>
      @if (evidence().activity) {
        <dt>AI quiz</dt>
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
