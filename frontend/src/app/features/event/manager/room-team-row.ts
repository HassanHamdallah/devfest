import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from "@angular/core";
import { RoomTeamRow } from "../../../core/event/event-models";
import { formatPenaltyMinutes, formatTime } from "../../../core/event/format";
import { NavIcon } from "../../../core/shell/nav-icon";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { EventJudgementControls } from "./judgement-controls";
import { EventPenaltyForm } from "./penalty-form";
import { EventPenaltyItem } from "./penalty-item";
import { EventTeamSubmission } from "./team-submission";

const PREVIEW_CHARACTERS = 90;

/**
 * One team on the manager board as a compact row: name, group, submitted time, clue result, a one-line
 * preview of the technical output, Pass/Fail, hints and penalties. Clicking the row (or "Details") expands
 * the full output, the clue verdict override, hints, penalties and history. The page owns which rows are open.
 */
@Component({
  selector: "event-room-team-row",
  imports: [
    EventJudgementControls,
    EventPenaltyForm,
    EventPenaltyItem,
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
  protected readonly activePenalties = computed(
    () => this.row().penalties.filter((penalty) => !penalty.voided).length,
  );
  protected readonly penaltyTotal = computed(
    () => `${formatPenaltyMinutes(this.row().activePenaltyMinutes)} active`,
  );

  /** A click on the summary's background toggles; clicks on its buttons and fields do their own thing. */
  protected toggleFromRow(event: MouseEvent): void {
    const target = event.target as HTMLElement;
    if (target.closest("button, a, input, select, textarea, label")) return;
    this.toggled.emit();
  }
}
