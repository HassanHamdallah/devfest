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
import { UiProgress } from "../../../../shared/ui/ui-progress/ui-progress";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { ControlAction, RoomActionError } from "./control-actions";
import { toRoomCardView } from "./control-view";

/**
 * One card per room (`data-room="<CODE>"`) in a 2 × 2 grid: the room's colour, open state, manager, arrival,
 * teams now, passed/total progress and open/close.
 */
@Component({
  selector: "app-room-cards",
  imports: [RouterLink, UiButton, UiCard, UiProgress, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section aria-labelledby="room-cards-heading">
    <h2 id="room-cards-heading">Rooms</h2>
    <div class="event-grid event-grid--rooms">
      @for (card of cards(); track card.room.id) {
        <ui-card
          fill
          [attr.data-room]="card.room.code"
          [class]="'room-tone--' + card.room.code"
        >
          <div ui-card-header class="room-card__header">
            <span class="room-card__strip" aria-hidden="true"></span>
            <h3>
              <span class="room-card__dot" aria-hidden="true"></span>
              {{ card.room.name }}
            </h3>
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
            @if (card.room.arrival; as arrival) {
              <dt>Arrival</dt>
              <dd>
                @if (arrival.ready) {
                  <span class="room-card__ready">All teams are here</span>
                } @else {
                  <span class="event-muted">Waiting for the manager</span>
                }
              </dd>
            }
            <dt>{{ card.groupText }}</dt>
            <dd>{{ card.teamsText }}</dd>
          </dl>
          <div class="room-card__progress">
            <div class="room-card__progress-text">
              <strong class="event-num">{{ card.progressText }}</strong>
              <span class="event-muted event-num">{{ card.reviewText }}</span>
            </div>
            <ui-progress
              [value]="card.room.passed"
              [max]="card.room.totalTeams"
              [label]="card.room.name + ' teams passed'"
            />
          </div>
          @if (roomError()?.roomId === card.room.id) {
            <p class="room-card__error" role="alert">
              {{ roomError()?.message }}
              <a [routerLink]="['/event/room', card.room.id]"
                >Open the {{ card.room.name }} board</a
              >
            </p>
          }
          <div ui-card-footer class="room-card__footer">
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
      display: flex;
      align-items: center;
      gap: var(--space-2);
      margin: 0;
      font-size: var(--font-size-lg);
    }
    .room-card__header {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
    }
    .room-card__strip {
      flex-basis: calc(100% + 2 * var(--space-card-inline));
      block-size: 8px;
      margin: calc(-1 * var(--space-card-block)) calc(-1 * var(--space-card-inline))
        var(--space-2);
      background: var(--room-color);
      border-block-end: 1.5px solid var(--color-outline);
    }
    .room-card__dot {
      inline-size: 12px;
      block-size: 12px;
      background: var(--room-color);
      border: 1.5px solid var(--color-outline);
      border-radius: 50%;
    }
    .room-card__facts {
      display: grid;
      grid-template-columns: max-content minmax(0, 1fr);
      gap: var(--space-2) var(--space-4);
      margin: 0 0 var(--space-4);
      font-size: var(--font-size-sm);
    }
    dt {
      color: var(--color-text-muted);
    }
    dd {
      margin: 0;
    }
    .room-card__missing {
      font-weight: var(--font-weight-semibold);
      color: var(--color-error);
    }
    .room-card__ready {
      font-weight: var(--font-weight-semibold);
      color: var(--color-success);
    }
    .room-card__progress {
      display: grid;
      gap: var(--space-2);
      --progress-color: var(--room-color);
    }
    .room-card__progress-text {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: var(--space-1) var(--space-3);
      font-size: var(--font-size-sm);
    }
    .room-card__error {
      margin: var(--space-3) 0 0;
      font-size: var(--font-size-sm);
      color: var(--color-danger);
    }
    .room-card__footer {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
    }
  `,
})
export class RoomCards {
  readonly rooms = input.required<readonly AdminRoomCard[]>();
  readonly busy = input(false);
  /** A refused "Open room" (shown on that room's card with a link to its board). */
  readonly roomError = input<RoomActionError | null>(null);
  readonly action = output<ControlAction>();

  protected readonly cards = computed(() => this.rooms().map(toRoomCardView));

  protected emit(kind: "openRoom" | "closeRoom", room: AdminRoomCard): void {
    if (!this.busy()) this.action.emit({ kind, room });
  }
}
