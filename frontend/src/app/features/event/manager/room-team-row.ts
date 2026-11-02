import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from "@angular/core";
import { RoomTeamRow } from "../../../core/event/event-models";
import { formatPenaltyMinutes } from "../../../core/event/format";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { EventJudgementControls } from "./judgement-controls";
import { EventPenaltyForm } from "./penalty-form";
import { EventPenaltyItem } from "./penalty-item";
import { EventTeamSubmission } from "./team-submission";

/**
 * One team on the manager board. Wide screens lay the four sections out as table-like columns;
 * narrow screens stack them into a card.
 */
@Component({
  selector: "event-room-team-row",
  imports: [
    EventJudgementControls,
    EventPenaltyForm,
    EventPenaltyItem,
    EventTeamSubmission,
    UiStatusChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    "[attr.data-team]": "row().teamName",
    "[class.now]": "row().scheduledNow",
  },
  styles: `
    :host {
      display: grid;
      gap: var(--space-4);
      padding: var(--space-4);
      background: var(--color-surface);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-card);
    }
    :host(.now) {
      border-color: var(--color-event-accent);
    }
    section {
      display: grid;
      align-content: start;
      gap: var(--space-2);
      min-width: 0;
    }
    h3 {
      margin: 0;
      font-size: var(--font-size-base);
      overflow-wrap: anywhere;
    }
    h4 {
      margin: 0;
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-semibold);
      color: var(--color-text-muted);
    }
    @media (min-width: 1100px) {
      :host {
        grid-template-columns:
          minmax(9rem, 0.8fr) minmax(0, 2fr) minmax(0, 1.4fr)
          minmax(0, 1.6fr);
      }
    }
  `,
  template: `<section>
      <h3>{{ row().teamName }}</h3>
      <div class="event-inline">
        @if (row().scheduledNow) {
          <ui-status-chip variant="accent">Now</ui-status-chip>
        }
        @if (row().disqualified) {
          <ui-status-chip variant="danger">Disqualified</ui-status-chip>
        }
        @if (row().exceptionRoom) {
          <ui-status-chip variant="warning">Exception: {{ row().exceptionRoom }}</ui-status-chip>
        }
        <span class="event-muted">{{ scheduleLine() }}</span>
      </div>
    </section>
    <section>
      <h4>Submission</h4>
      <event-team-submission
        [roomId]="roomId()"
        [submission]="row().latestSubmission"
        [submissionCount]="row().submissionCount"
        [editable]="contentEditable()"
        (changed)="changed.emit()"
      />
    </section>
    <section>
      <h4>Technical output — your decision</h4>
      <event-judgement-controls
        [roomId]="roomId()"
        [judgement]="row().judgement"
        [hintPenaltyMismatch]="row().hintPenaltyMismatch"
        (changed)="changed.emit()"
      />
      @if (row().judgement.note) {
        <span class="event-muted">Note: {{ row().judgement.note }}</span>
      }
    </section>
    <section>
      <h4>Penalties · {{ penaltyTotal() }}</h4>
      <event-penalty-form
        [roomId]="roomId()"
        [teamId]="row().teamId"
        (changed)="changed.emit()"
      />
      @for (penalty of row().penalties; track penalty.id) {
        <event-penalty-item [penalty]="penalty" (changed)="changed.emit()" />
      } @empty {
        <span class="event-muted">No penalties in this room.</span>
      }
    </section>`,
})
export class EventRoomTeamRow {
  readonly roomId = input.required<number>();
  readonly row = input.required<RoomTeamRow>();
  /** False once the final has opened: clue verdicts are then final. */
  readonly contentEditable = input.required<boolean>();
  /** Any write in this row finished; the page reloads its board. */
  readonly changed = output<void>();

  protected readonly scheduleLine = computed(() => {
    const { groupCode, scheduledRound } = this.row();
    return `Group ${groupCode} · round ${scheduledRound}`;
  });

  protected readonly penaltyTotal = computed(
    () => `${formatPenaltyMinutes(this.row().activePenaltyMinutes)} active`,
  );
}
