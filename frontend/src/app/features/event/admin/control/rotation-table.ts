import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from "@angular/core";
import { AdminBoard } from "../../../../core/event/event-models";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { rotationColumns, rotationLines } from "./control-view";

/** Groups × rounds grid of rooms; the running round's column is highlighted. */
@Component({
  selector: "app-rotation-table",
  imports: [UiStatusChip],
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
                {{ cell.roomName }}
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
      color: var(--color-event-accent-text);
      font-weight: var(--font-weight-semibold);
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
