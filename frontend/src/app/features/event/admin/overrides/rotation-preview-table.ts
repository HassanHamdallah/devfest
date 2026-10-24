import { ChangeDetectionStrategy, Component, input } from "@angular/core";
import {
  OrganizationRoom,
  RoomCode,
  RotationPreviewView,
} from "../../../../core/event/event-models";

/** The server's preview of a swap or move: rooms done, rooms of the remaining rounds, warnings. */
@Component({
  selector: "app-rotation-preview-table",
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="event-table-wrap">
    <table>
      <caption>
        {{
          caption()
        }}
      </caption>
      <thead>
        <tr>
          <th scope="col">Team</th>
          <th scope="col">Group</th>
          <th scope="col">Rooms done</th>
          <th scope="col">Remaining rounds</th>
          <th scope="col">Warnings</th>
        </tr>
      </thead>
      <tbody>
        @for (row of preview(); track row.teamId) {
          <tr>
            <th scope="row">{{ row.teamName }}</th>
            <td>{{ row.fromGroup }} → {{ row.toGroup }}</td>
            <td>{{ names(row.visited) || "None yet" }}</td>
            <td>{{ remaining(row) || "None" }}</td>
            <td>
              @for (warning of row.warnings; track warning) {
                <div class="rotation-preview__warning">{{ warning }}</div>
              } @empty {
                <span class="event-muted">No warnings</span>
              }
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
    .rotation-preview__warning {
      color: var(--color-warning-text);
    }
  `,
})
export class RotationPreviewTable {
  readonly preview = input.required<readonly RotationPreviewView[]>();
  readonly rooms = input.required<readonly OrganizationRoom[]>();
  readonly caption = input("Preview");

  protected names(codes: readonly RoomCode[]): string {
    return codes.map((code) => this.roomName(code)).join(", ");
  }

  protected remaining(row: RotationPreviewView): string {
    return row.remaining
      .map((visit) => `R${visit.round}: ${this.roomName(visit.room)}`)
      .join(", ");
  }

  private roomName(code: RoomCode): string {
    return this.rooms().find((room) => room.code === code)?.name ?? code;
  }
}
