import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from "@angular/core";
import { message } from "../../../core/api";
import { EventApi } from "../../../core/event/event-api";
import { RoomBoard } from "../../../core/event/event-models";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";

/**
 * Arrival check while the round is ASSIGNED (`data-arrival-card`): the teams expected in this room and "All teams
 * are here", which tells the admin the room is ready for the timer. "Undo" withdraws it until the timer starts.
 */
@Component({
  selector: "event-arrival-card",
  imports: [UiAlert, UiButton, UiCard, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "data-arrival-card": "" },
  styles: `
    .arrival {
      display: grid;
      gap: var(--space-3);
    }
    h2,
    p {
      margin: 0;
    }
  `,
  template: `<ui-card>
    <h2 ui-card-header>Arrival check</h2>
    <div class="arrival">
      <p>
        Teams expected now:
        <strong>{{ expected() }}</strong>
      </p>
      <div class="event-inline">
        @if (ready()) {
          <ui-status-chip variant="success">Ready ✓</ui-status-chip>
          <span class="event-muted">{{ readyLine() }}</span>
          <ui-button
            variant="ghost"
            size="sm"
            [loading]="saving()"
            (click)="setReady(false)"
            >Undo</ui-button
          >
        } @else {
          <ui-button [loading]="saving()" (click)="setReady(true)"
            >All teams are here</ui-button
          >
        }
      </div>
      @if (error(); as text) {
        <ui-alert variant="error">{{ text }}</ui-alert>
      }
    </div>
  </ui-card>`,
})
export class EventArrivalCard {
  private readonly eventApi = inject(EventApi);

  readonly board = input.required<RoomBoard>();
  readonly changed = output<void>();

  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly ready = computed(() => this.board().arrival?.ready ?? false);
  protected readonly expected = computed(() => {
    const names = this.board().teamsExpectedNow;
    return names.length ? names.join(", ") : "no active teams";
  });
  protected readonly readyLine = computed(() => {
    const arrival = this.board().arrival;
    if (!arrival?.at) return "";
    const time = new Date(arrival.at).toLocaleTimeString([], {
      hour: "2-digit",
      minute: "2-digit",
    });
    return `${arrival.by ?? "Confirmed"} at ${time}`;
  });

  protected async setReady(ready: boolean): Promise<void> {
    if (this.saving()) return;
    this.saving.set(true);
    this.error.set(null);
    try {
      await this.eventApi.setArrival(this.board().room.id, ready);
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.saving.set(false);
      this.changed.emit();
    }
  }
}
