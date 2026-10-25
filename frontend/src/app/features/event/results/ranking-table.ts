import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
} from "@angular/core";
import { PublicRankingRow } from "../../../core/event/event-models";
import { TeamDrawerService } from "../../../core/shell/team-drawer.service";
import {
  formatAdjustedSeconds,
  formatPenaltyMinutes,
} from "../../../core/event/format";

/** The admin's rows also carry the team id, which lets the team name open the team drawer. */
type RankingInput = PublicRankingRow & { teamId?: number };

interface RankingLine {
  teamId: number | null;
  rank: string;
  teamName: string;
  groupCode: string;
  clues: string;
  penalties: string;
  time: string;
  finished: boolean;
}

/** Display text of one ranking row: rank "—" for teams still playing, "Disqualified" (never ranked). */
function toRankingLine(row: RankingInput): RankingLine {
  return {
    teamId: row.teamId ?? null,
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
            <td class="event-num" [class.ranking-table__podium]="line.rank === '1'">{{ line.rank }}</td>
            <th scope="row">
              @if (line.teamId !== null) {
                <button
                  type="button"
                  class="event-link-button"
                  (click)="drawer.open(line.teamId)"
                >
                  {{ line.teamName }}<span class="event-sr-only">: show details</span>
                </button>
              } @else {
                {{ line.teamName }}
              }
            </th>
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
    .ranking-table__podium {
      font-weight: var(--font-weight-bold);
    }
    td:nth-child(n + 4) {
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class RankingTable {
  protected readonly drawer = inject(TeamDrawerService);
  readonly rows = input.required<readonly RankingInput[]>();

  protected readonly lines = computed(() => this.rows().map(toRankingLine));
}
