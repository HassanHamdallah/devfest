import { ChangeDetectionStrategy, Component, inject, input } from "@angular/core";
import { RankingRow, RoomScoreLine } from "../../../../core/event/event-models";
import { formatPoints } from "../../../../core/event/format";
import { TeamDrawerService } from "../../../../core/shell/team-drawer.service";

/**
 * The overall scoreboard: for every team its place, the four room lines (base, the deductions taken off, what is
 * left), the final's parts and the total out of 100. Organizers only; a disqualified team is listed last, unranked.
 */
@Component({
  selector: "app-scoreboard-table",
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="event-table-wrap">
    <table data-scoreboard>
      <caption>
        Scoreboard
      </caption>
      <thead>
        <tr>
          <th scope="col">Rank</th>
          <th scope="col">Team</th>
          <th scope="col">AI</th>
          <th scope="col">Problem solving</th>
          <th scope="col">Linux</th>
          <th scope="col">Hardware</th>
          <th scope="col">Rooms / 60</th>
          <th scope="col">Clues</th>
          <th scope="col">Crypto</th>
          <th scope="col">Suspect</th>
          <th scope="col">Time</th>
          <th scope="col">Final / 40</th>
          <th scope="col">Total / 100</th>
        </tr>
      </thead>
      <tbody>
        @for (row of rows(); track row.scores.teamId) {
          <tr [attr.data-team]="row.scores.teamName" [class.out]="row.scores.disqualified">
            <td class="event-num">{{ row.scores.disqualified ? "Disqualified" : (row.rank ?? "—") }}</td>
            <th scope="row">
              <button type="button" class="event-link-button" (click)="drawer.open(row.scores.teamId)">
                {{ row.scores.teamName }}<span class="event-sr-only">: show details</span>
              </button>
            </th>
            @for (room of row.scores.rooms; track room.roomId) {
              <td class="event-num" [attr.data-room]="room.code" [title]="roomTitle(room)">
                <strong>{{ points(room.score) }}</strong>
                @if (room.hintDeduction || room.cheatingDeduction) {
                  <small class="deduction" data-deduction-line
                    >{{ points(room.base) }}
                    @if (room.hintDeduction) {
                      − {{ points(room.hintDeduction) }}
                    }
                    @if (room.cheatingDeduction) {
                      − {{ points(room.cheatingDeduction) }}
                    }
                  </small>
                }
              </td>
            }
            <td class="event-num">{{ points(row.scores.roomsTotal) }}</td>
            <td class="event-num" data-clues-solved>{{ row.scores.finalScore.cluesSolved }} of 4</td>
            <td class="event-num">{{ points(row.scores.finalScore.clues) }}</td>
            <td class="event-num">{{ points(row.scores.finalScore.suspect) }}</td>
            <td class="event-num">{{ points(row.scores.finalScore.time) }}</td>
            <td class="event-num">{{ points(row.scores.finalScore.total) }}</td>
            <td class="event-num total" data-total>{{ points(row.scores.total) }}</td>
          </tr>
        } @empty {
          <tr>
            <td colspan="13" class="muted">No active teams yet.</td>
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
    td small {
      display: block;
      font-size: var(--font-size-xs);
      color: var(--color-text-muted);
    }
    .total {
      font-weight: var(--font-weight-bold);
    }
    .out,
    .muted {
      color: var(--color-text-muted);
    }
  `,
})
export class ScoreboardTable {
  protected readonly drawer = inject(TeamDrawerService);
  readonly rows = input.required<readonly RankingRow[]>();

  protected points(value: number): string {
    return formatPoints(value);
  }

  protected roomTitle(room: RoomScoreLine): string {
    return `${room.name}: base ${formatPoints(room.base)}, hints ${room.hints}, cheating ${room.cheating ? "yes" : "no"}`;
  }
}
