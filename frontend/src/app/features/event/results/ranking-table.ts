import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from "@angular/core";
import { PublicRankingRow } from "../../../core/event/event-models";
import {
  formatAdjustedSeconds,
  formatPenaltyMinutes,
} from "../../../core/event/format";

interface RankingLine {
  rank: string;
  teamName: string;
  groupCode: string;
  clues: string;
  penalties: string;
  time: string;
  finished: boolean;
}

/** Display text of one ranking row: rank "—" for teams still playing, "Disqualified" (never ranked). */
function toRankingLine(row: PublicRankingRow): RankingLine {
  return {
    rank: row.disqualified ? "Disqualified" : row.rank === null ? "—" : String(row.rank),
    teamName: row.teamName,
    groupCode: row.groupCode,
    clues: `${row.clues} of 4`,
    penalties: formatPenaltyMinutes(row.penaltyMinutes),
    time: row.finished
      ? formatAdjustedSeconds(row.adjustedSeconds)
      : "Not finished",
    finished: row.finished,
  };
}

/** The ranking table shared by the admin control page and the results page (caption "Ranking"). */
@Component({
  selector: "app-ranking-table",
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="event-table-wrap">
    <table>
      <caption>
        Ranking
      </caption>
      <thead>
        <tr>
          <th scope="col">Rank</th>
          <th scope="col">Team</th>
          <th scope="col">Group</th>
          <th scope="col">Clues</th>
          <th scope="col">Penalties</th>
          <th scope="col">Adjusted time</th>
        </tr>
      </thead>
      <tbody>
        @for (line of lines(); track $index) {
          <tr [attr.data-team]="line.teamName">
            <td>{{ line.rank }}</td>
            <th scope="row">{{ line.teamName }}</th>
            <td>{{ line.groupCode }}</td>
            <td>{{ line.clues }}</td>
            <td>{{ line.penalties }}</td>
            <td [class.ranking-table__unfinished]="!line.finished">
              {{ line.time }}
            </td>
          </tr>
        } @empty {
          <tr>
            <td colspan="6" class="ranking-table__empty">
              No active teams yet.
            </td>
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
    .ranking-table__unfinished,
    .ranking-table__empty {
      color: var(--color-text-muted);
    }
  `,
})
export class RankingTable {
  readonly rows = input.required<readonly PublicRankingRow[]>();

  protected readonly lines = computed(() => this.rows().map(toRankingLine));
}
