import { ChangeDetectionStrategy, Component, input } from "@angular/core";
import { RoomEvidence } from "../../../core/event/event-models";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiRoomChip } from "../../../shared/ui/ui-room-chip/ui-room-chip";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { EventMaskedClue } from "./masked-clue";

/**
 * "Room clues" on the final page: the four rooms in room colours, each collected clue behind the eye toggle. Once
 * the team has started the case the list is collapsed (native details), since the clues were used to open it.
 */
@Component({
  selector: "event-final-evidence",
  imports: [EventMaskedClue, UiCard, UiRoomChip, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    summary {
      cursor: pointer;
      min-block-size: 32px;
      color: var(--color-text-muted);
    }
    ul {
      display: grid;
      gap: var(--space-2);
      margin: var(--space-2) 0 0;
      padding: 0;
      list-style: none;
    }
    li {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
      padding: var(--space-2) var(--space-3);
      background: var(--room-tint);
      border-inline-start: 6px solid var(--room-color);
      border-radius: var(--radius-sm);
    }
  `,
  template: `<ui-card>
    <h2 ui-card-header>Room clues</h2>
    <details [open]="!collapsed()" data-room-clues>
      <summary>{{ collected() }} of {{ evidence().length }} collected</summary>
      <ul aria-label="Clues by room">
        @for (room of evidence(); track room.roomCode) {
          <li [class]="'room-tone--' + room.roomCode" [attr.data-final-evidence]="room.roomCode">
            <ui-room-chip [code]="room.roomCode" [name]="room.roomName" />
            @if (room.clueValue; as clue) {
              <event-masked-clue [value]="clue" />
            } @else {
              <ui-status-chip variant="neutral">Clue not collected</ui-status-chip>
            }
          </li>
        }
      </ul>
    </details>
  </ui-card>`,
})
export class EventFinalEvidence {
  readonly evidence = input.required<readonly RoomEvidence[]>();
  /** True once the team has started the case: the list starts closed. */
  readonly collapsed = input(false);

  protected collected(): number {
    return this.evidence().filter((room) => room.clueValue).length;
  }
}
