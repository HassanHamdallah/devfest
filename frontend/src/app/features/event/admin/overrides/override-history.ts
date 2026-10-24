import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from "@angular/core";
import {
  OrganizationRoom,
  OverrideAuditView,
  TeamFlagsView,
} from "../../../../core/event/event-models";
import { formatTime } from "../../../../core/event/format";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { SECTION_STYLES } from "./override-run";

const ACTION_LABELS: Record<string, string> = {
  EVENT_TEAMS_SWAPPED: "Teams swapped",
  EVENT_TEAM_MOVED: "Team moved",
  EVENT_ROOM_EXCEPTION_SET: "Room exception",
  EVENT_ROOM_EXCEPTION_CLEARED: "Room exception cleared",
  EVENT_CLUE_GRANTED: "Clue granted",
  EVENT_CLUE_REVOKED: "Clue revoked",
  EVENT_TIME_ADJUSTMENT: "Time adjustment",
  EVENT_TEAM_DISQUALIFIED: "Disqualified",
  EVENT_TEAM_REINSTATED: "Reinstated",
  EVENT_FINAL_RESET: "Final reset",
  EVENT_TEAM_RESET: "Team reset",
  EVENT_RESET: "Event reset",
  EVENT_SNAPSHOT: "Snapshot",
};

/** Teams with override markers (admin only: "Manual" clues etc.) and the last 20 overrides with reasons. */
@Component({
  selector: "app-override-history",
  imports: [UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [SECTION_STYLES],
  template: `<section class="override-section" aria-labelledby="flags-heading">
    <h2 id="flags-heading">Teams with overrides</h2>
    @for (flag of flagged(); track flag.teamId) {
      <div class="event-inline" [attr.data-flags]="flag.teamName">
        <strong>{{ flag.teamName }}</strong>
        @if (flag.disqualified) {
          <ui-status-chip variant="danger">Disqualified</ui-status-chip>
        }
        @if (flag.exceptionRoom) {
          <ui-status-chip variant="accent">Exception: {{ roomName(flag) }}</ui-status-chip>
        }
        @if (flag.manualClues) {
          <ui-status-chip>Manual clues: {{ flag.manualClues }}</ui-status-chip>
        }
        @if (flag.timeAdjustmentMinutes) {
          <ui-status-chip>Time adjustment: {{ signed(flag.timeAdjustmentMinutes) }} min</ui-status-chip>
        }
      </div>
    } @empty {
      <p class="event-muted">No team has an active override.</p>
    }
    <div class="event-table-wrap">
      <table>
        <caption>
          Recent overrides
        </caption>
        <thead>
          <tr>
            <th scope="col">Time</th>
            <th scope="col">By</th>
            <th scope="col">Override</th>
            <th scope="col">Target</th>
            <th scope="col">Reason</th>
          </tr>
        </thead>
        <tbody>
          @for (entry of recent(); track $index) {
            <tr>
              <td>{{ time(entry) }}</td>
              <td>{{ entry.by ?? "—" }}</td>
              <td>{{ label(entry) }}</td>
              <td>{{ entry.target }}</td>
              <td>{{ entry.reason ?? "—" }}</td>
            </tr>
          } @empty {
            <tr>
              <td colspan="5" class="event-muted">No overrides yet.</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  </section>`,
})
export class OverrideHistory {
  readonly flags = input.required<readonly TeamFlagsView[]>();
  readonly recent = input.required<readonly OverrideAuditView[]>();
  readonly rooms = input.required<readonly OrganizationRoom[]>();

  protected readonly flagged = computed(() =>
    this.flags().filter(
      (flag) =>
        flag.disqualified ||
        flag.exceptionRoom !== null ||
        flag.manualClues > 0 ||
        flag.timeAdjustmentMinutes !== 0,
    ),
  );

  protected roomName(flag: TeamFlagsView): string {
    return this.rooms().find((room) => room.code === flag.exceptionRoom)?.name ?? "";
  }

  protected signed(minutes: number): string {
    return minutes > 0 ? `+${minutes}` : String(minutes);
  }

  protected time(entry: OverrideAuditView): string {
    return formatTime(entry.at);
  }

  protected label(entry: OverrideAuditView): string {
    return ACTION_LABELS[entry.action] ?? entry.action;
  }
}
