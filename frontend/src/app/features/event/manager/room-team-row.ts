import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from "@angular/core";
import { RoomTeamRow } from "../../../core/event/event-models";
import { formatTime } from "../../../core/event/format";
import { NavIcon } from "../../../core/shell/nav-icon";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { EventJudgementControls } from "./judgement-controls";
import { EventDeductionItem } from "./deduction-item";
import { EventManualScore } from "./manual-score";
import { EventPointsLedger } from "./points-ledger";
import { EventTeamDeductions } from "./team-deductions";
import { EventTeamSubmission } from "./team-submission";

const PREVIEW_CHARACTERS = 90;

/**
 * One team on the manager board as a compact card: name, group, submitted time, clue result, a one-line preview of
 * the technical output, Pass/Fail and "Hints: n of 2". A team that is in the room now carries its deduction actions
 * ("Hint given (-2)", "Cheating (-7.5)") and list right on the card. Clicking the row (or "Details") expands the full
 * output, the clue verdict override and, in a room without an automatic score, the score input. The page owns which
 * rows are open.
 */
@Component({
  selector: "event-room-team-row",
  imports: [
    EventDeductionItem,
    EventJudgementControls,
    EventManualScore,
    EventPointsLedger,
    EventTeamDeductions,
    EventTeamSubmission,
    NavIcon,
    UiStatusChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    "[attr.data-team]": "row().teamName",
    "[attr.data-team-id]": "row().teamId",
    "[class.now]": "row().scheduledNow",
    "[class.open]": "expanded()",
  },
  templateUrl: "./room-team-row.html",
  styleUrl: "./room-team-row.css",
})
export class EventRoomTeamRow {
  readonly roomId = input.required<number>();
  readonly row = input.required<RoomTeamRow>();
  /** False once the final has opened: clue verdicts are then final. */
  readonly contentEditable = input.required<boolean>();
  /** The room has no automatic score: its manager types one for each team. */
  readonly manualScoring = input(false);
  /** The room runs on a points ledger (the Hardware room): the card shows the total, Add / Subtract and Undo last. */
  readonly pointsLedger = input(false);
  readonly expanded = input(false);
  /** Any write in this row finished; the page reloads its board. */
  readonly changed = output<void>();
  readonly toggled = output<void>();

  protected readonly detailsId = computed(() => `team-details-${this.row().teamId}`);
  protected readonly submittedAt = computed(() => {
    const latest = this.row().latestSubmission;
    return latest ? formatTime(latest.receivedAt) : null;
  });
  protected readonly preview = computed(() => {
    const output = this.row().latestSubmission?.technicalOutput ?? "";
    const firstLine = output.split("\n").find((line) => line.trim()) ?? "";
    return firstLine.length > PREVIEW_CHARACTERS
      ? `${firstLine.slice(0, PREVIEW_CHARACTERS)}…`
      : firstLine;
  });
  /** A click on the summary's background toggles; clicks on its buttons and fields do their own thing. */
  protected toggleFromRow(event: MouseEvent): void {
    const target = event.target as HTMLElement;
    if (target.closest("button, a, input, select, textarea, label")) return;
    this.toggled.emit();
  }
}
