import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from "@angular/core";
import { AdminBoard } from "../../../../core/event/event-models";
import { UiRoomChip } from "../../../../shared/ui/ui-room-chip/ui-room-chip";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { rotationColumns, rotationLines } from "./control-view";

/** Groups × rounds grid of room chips in the room colours; the running round's column is highlighted. */
@Component({
  selector: "app-rotation-table",
  imports: [UiRoomChip, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="event-table-wrap">
    <table>
      <caption>
        Rotation
      </caption>
      <thead>
        <tr>
          <th scope="col">Group</th>
          @for (column of columns(); track column.round) {
            <th scope="col" [class.rotation__current]="column.current">
              Round {{ column.round }}
              @if (column.current) {
                <ui-status-chip variant="accent">Now</ui-status-chip>
              }
            </th>
          }
        </tr>
      </thead>
      <tbody>
        @for (line of lines(); track line.groupCode) {
          <tr>
            <th scope="row">Group {{ line.groupCode }}</th>
            @for (cell of line.cells; track $index) {
              <td [class.rotation__current]="cell.current">
                <ui-room-chip size="sm" [code]="cell.code" [name]="cell.roomName" />
              </td>
            }
          </tr>
        }
      </tbody>
    </table>
  </div>`,
  styles: `
    th,
    td {
      white-space: nowrap;
    }
    th[scope="row"] {
      font-size: inherit;
      letter-spacing: normal;
      color: var(--color-text);
      background: none;
    }
    .rotation__current {
      background: var(--color-event-accent-subtle);
      box-shadow:
        inset 2px 0 0 var(--color-event-accent),
        inset -2px 0 0 var(--color-event-accent);
    }
    thead .rotation__current {
      color: var(--color-event-accent-text);
      font-weight: var(--font-weight-bold);
    }
    tbody tr:hover > .rotation__current {
      background: var(--color-event-accent-subtle);
    }
    ui-status-chip {
      margin-left: var(--space-1);
      letter-spacing: normal;
    }
  `,
})
export class RotationTable {
  readonly board = input.required<AdminBoard>();

  protected readonly columns = computed(() =>
    rotationColumns(this.board().state.currentRound),
  );
  protected readonly lines = computed(() => rotationLines(this.board()));
}
