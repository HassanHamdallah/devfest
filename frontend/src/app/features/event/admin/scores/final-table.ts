import { ChangeDetectionStrategy, Component, inject, input } from "@angular/core";
import { FinalBoardRow } from "../../../../core/event/event-models";
import { FINAL_STAGE_LABELS, formatAdjustedSeconds, formatPoints, formatTime } from "../../../../core/event/format";
import { TeamDrawerService } from "../../../../core/shell/team-drawer.service";

/**
 * The live final table: every team's status and stage, its attempts, what it answered (verdicts, suspect) and the
 * parts of its final score. Organizers only: it shows correctness that a team never sees.
 */
@Component({
  selector: "app-final-table",
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="event-table-wrap">
    <table data-final-table>
      <caption>
        Final
      </caption>
      <thead>
        <tr>
          <th scope="col">Team</th>
          <th scope="col">Status</th>
          <th scope="col">Stage</th>
          <th scope="col">Clues</th>
          <th scope="col">Layers</th>
          <th scope="col">Unlock tries</th>
          <th scope="col">Layer tries</th>
          <th scope="col">Recovery tries</th>
          <th scope="col">E03</th>
          <th scope="col">E04</th>
          <th scope="col">Suspect</th>
          <th scope="col">Active time</th>
          <th scope="col">Finished</th>
          <th scope="col">Layers pts</th>
          <th scope="col">Verdict pts</th>
          <th scope="col">Suspect pts</th>
          <th scope="col">Time pts</th>
          <th scope="col">Final / 40</th>
        </tr>
      </thead>
      <tbody>
        @for (row of rows(); track row.teamId) {
          <tr [attr.data-team]="row.teamName" [attr.data-stage]="row.stage">
            <th scope="row">
              <button type="button" class="event-link-button" (click)="drawer.open(row.teamId)">
                {{ row.teamName }}<span class="event-sr-only">: show details</span>
              </button>
            </th>
            <td data-status>{{ statusLabel(row) }}</td>
            <td data-stage-cell>{{ row.stage }}</td>
            <td class="event-num">{{ row.clues }}</td>
            <td class="event-num">{{ row.layersSolved }} of 4</td>
            <td class="event-num">{{ row.unlockAttempts }} ({{ row.unlockWrong }} wrong)</td>
            <td class="event-num">{{ row.layerAttempts }} ({{ row.layerWrong }} wrong)</td>
            <td class="event-num">{{ row.recoveryAttempts }}</td>
            <td>{{ verdict(row.verdictE03, row.verdictE03Correct) }}</td>
            <td>{{ verdict(row.verdictE04, row.verdictE04Correct) }}</td>
            <td data-suspect>{{ suspect(row) }}</td>
            <td class="event-num">{{ active(row) }}</td>
            <td class="event-num">{{ finished(row) }}</td>
            <td class="event-num">{{ points(row.layersPoints) }}</td>
            <td class="event-num">{{ points(row.verdictsPoints) }}</td>
            <td class="event-num">{{ points(row.suspectPoints) }}</td>
            <td class="event-num">{{ points(row.timePoints) }}</td>
            <td class="event-num total" data-final-score>{{ points(row.finalScore) }}</td>
          </tr>
        } @empty {
          <tr>
            <td colspan="18" class="muted">No active teams yet.</td>
          </tr>
        }
      </tbody>
    </table>
  </div>`,
  styles: `
    th[scope="row"] {
      font-size: inherit;
      letter-spacing: normal;
      color: var(--color-text);
      background: none;
    }
    td,
    th {
      white-space: nowrap;
    }
    .total {
      font-weight: var(--font-weight-bold);
    }
    .muted {
      color: var(--color-text-muted);
    }
  `,
})
export class FinalTable {
  protected readonly drawer = inject(TeamDrawerService);
  readonly rows = input.required<readonly FinalBoardRow[]>();

  protected statusLabel(row: FinalBoardRow): string {
    return FINAL_STAGE_LABELS[row.status] + (row.disqualified ? " (disqualified)" : "");
  }

  protected verdict(value: "YES" | "NO" | null, correct: boolean | null): string {
    return value === null ? "—" : `${value} (${correct ? "right" : "wrong"})`;
  }

  protected suspect(row: FinalBoardRow): string {
    return row.suspect === null ? "—" : `${row.suspect} (${row.suspectCorrect ? "right" : "wrong"})`;
  }

  protected active(row: FinalBoardRow): string {
    return formatAdjustedSeconds(row.activeSeconds);
  }

  protected finished(row: FinalBoardRow): string {
    if (row.finishedAt === null) return row.finishedBy === "TIME_UP" ? "Time up" : row.finishedBy === "CLOSED" ? "Closed" : "—";
    return formatTime(row.finishedAt);
  }

  protected points(value: number): string {
    return formatPoints(value);
  }
}
