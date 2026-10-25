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
import { ToastService } from "../../../shared/ui/toast/toast.service";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";

/**
 * Arrival check while the round is ASSIGNED (`data-arrival-card`): the teams expected in this room and "All teams
 * are here", which tells the admin the room is ready for the timer. "Undo" withdraws it until the timer starts.
 */
@Component({
  selector: "event-arrival-card",
  imports: [UiAlert, UiButton, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "data-arrival-card": "" },
  styles: `
    .arrival {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3) var(--space-5);
      padding: var(--space-4) var(--space-5);
      background: var(--color-warning-subtle);
      border: var(--border-outline-thin);
      border-inline-start: 6px solid var(--brand-yellow);
      border-radius: var(--radius-card);
    }
    .arrival--ready {
      background: var(--color-success-subtle);
      border-inline-start-color: var(--brand-green);
    }
    .arrival__text {
      display: grid;
      gap: var(--space-1);
      min-inline-size: 0;
    }
    h2,
    p {
      margin: 0;
    }
    h2 {
      font-size: var(--font-size-base);
    }
    ui-alert {
      flex-basis: 100%;
    }
  `,
  template: `<section
    class="arrival"
    [class.arrival--ready]="ready()"
    aria-labelledby="arrival-heading"
  >
    <div class="arrival__text">
      <h2 id="arrival-heading">Arrival check</h2>
      <p>
        Teams expected now:
        <strong>{{ expected() }}</strong>
      </p>
    </div>
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
        <ui-button size="lg" [loading]="saving()" (click)="setReady(true)"
          >All teams are here</ui-button
        >
      }
    </div>
    @if (error(); as text) {
      <ui-alert variant="error" [dismissible]="true">{{ text }}</ui-alert>
    }
  </section>`,
})
export class EventArrivalCard {
  private readonly eventApi = inject(EventApi);
  private readonly toasts = inject(ToastService);

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
      this.toasts.show(ready ? "Room marked ready" : "Ready withdrawn");
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.saving.set(false);
      this.changed.emit();
    }
  }
}
