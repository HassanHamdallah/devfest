import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from "@angular/core";
import { RouterLink } from "@angular/router";
import { AdminRoomCard } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { ControlAction } from "./control-actions";
import { toRoomCardView } from "./control-view";

/** One card per room (`data-room="<CODE>"`): open state, manager, teams now, progress, open/close. */
@Component({
  selector: "app-room-cards",
  imports: [RouterLink, UiButton, UiCard, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section aria-labelledby="room-cards-heading">
    <h2 id="room-cards-heading">Rooms</h2>
    <div class="event-grid">
      @for (card of cards(); track card.room.id) {
        <ui-card [attr.data-room]="card.room.code">
          <div ui-card-header class="room-card__header">
            <h3>{{ card.room.name }}</h3>
            @if (card.room.open) {
              <ui-status-chip variant="success">Open</ui-status-chip>
            } @else {
              <ui-status-chip variant="warning">Closed</ui-status-chip>
            }
          </div>
          <dl class="room-card__facts">
            <dt>Manager</dt>
            <dd [class.room-card__missing]="!card.hasManager">
              {{ card.managerText }}
            </dd>
            <dt>{{ card.groupText }}</dt>
            <dd>{{ card.teamsText }}</dd>
            <dt>Progress</dt>
            <dd>
              {{ card.progressText }}
              <span class="event-muted">· {{ card.reviewText }}</span>
            </dd>
          </dl>
          <div ui-card-footer class="event-inline">
            @if (card.room.open) {
              <ui-button
                variant="secondary"
                [disabled]="busy()"
                (click)="emit('closeRoom', card.room)"
                >Close room</ui-button
              >
            } @else {
              <ui-button
                [disabled]="busy()"
                (click)="emit('openRoom', card.room)"
                >Open room</ui-button
              >
            }
            <a [routerLink]="['/event/room', card.room.id]"
              >View board<span class="event-sr-only">
                for {{ card.room.name }}</span
              ></a
            >
          </div>
        </ui-card>
      }
    </div>
  </section>`,
  styles: `
    h2 {
      margin: 0 0 var(--space-3);
    }
    h3 {
      margin: 0;
      font-size: var(--font-size-lg);
    }
    .room-card__header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
    }
    .room-card__facts {
      display: grid;
      gap: var(--space-1);
      margin: 0;
      font-size: var(--font-size-sm);
    }
    dt {
      font-weight: var(--font-weight-semibold);
    }
    dd {
      margin: 0 0 var(--space-2);
    }
    .room-card__missing {
      color: var(--color-warning-text);
    }
    .event-inline {
      justify-content: space-between;
    }
  `,
})
export class RoomCards {
  readonly rooms = input.required<readonly AdminRoomCard[]>();
  readonly busy = input(false);
  readonly action = output<ControlAction>();

  protected readonly cards = computed(() => this.rooms().map(toRoomCardView));

  protected emit(kind: "openRoom" | "closeRoom", room: AdminRoomCard): void {
    if (!this.busy()) this.action.emit({ kind, room });
  }
}
