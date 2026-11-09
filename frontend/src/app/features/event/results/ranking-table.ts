import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
} from "@angular/core";
import { PublicRankingRow } from "../../../core/event/event-models";
import { formatPoints } from "../../../core/event/format";
import { TeamDrawerService } from "../../../core/shell/team-drawer.service";

/** The admin's rows also carry the team id, which lets the team name open the team drawer. */
type RankingInput = PublicRankingRow & { teamId?: number };

interface RankingLine {
  teamId: number | null;
  rank: string;
  teamName: string;
  groupCode: string;
  rooms: string[];
  final: string;
  total: string;
  disqualified: boolean;
}

/** Display text of one ranking row: rank "Disqualified" for a team outside the ranking. */
function toRankingLine(row: RankingInput): RankingLine {
  return {
    teamId: row.teamId ?? null,
    rank: row.disqualified ? "Disqualified" : row.rank === null ? "—" : String(row.rank),
    teamName: row.teamName,
    groupCode: row.groupCode,
    rooms: row.roomScores.map((score) => formatPoints(score)),
    final: formatPoints(row.finalScore),
    total: formatPoints(row.total),
    disqualified: row.disqualified,
  };
}

/**
 * The ranking table shared by the admin control page and the results page (caption "Ranking"): the four room scores
 * (out of 15 each), the final (out of 40) and the total (out of 100).
 */
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
          <th scope="col">AI</th>
          <th scope="col">Problem solving</th>
          <th scope="col">Linux</th>
          <th scope="col">Hardware</th>
          <th scope="col">Final</th>
          <th scope="col">Total / 100</th>
        </tr>
      </thead>
      <tbody>
        @for (line of lines(); track $index) {
          <tr [attr.data-team]="line.teamName" [class.ranking-table__out]="line.disqualified">
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
            @for (score of line.rooms; track $index) {
              <td class="event-num">{{ score }}</td>
            }
            <td class="event-num">{{ line.final }}</td>
            <td class="event-num ranking-table__total">{{ line.total }}</td>
          </tr>
        } @empty {
          <tr>
            <td colspan="9" class="ranking-table__empty">
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
    .ranking-table__empty,
    .ranking-table__out {
      color: var(--color-text-muted);
    }
    .ranking-table__podium,
    .ranking-table__total {
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
