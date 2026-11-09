import { ChangeDetectionStrategy, Component, inject, input, output, signal } from "@angular/core";
import { message } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";

/** Screen 2: the mission and the equipment. "Start case" opens layer 1. */
@Component({
  selector: "event-final-mission",
  imports: [UiAlert, UiButton, UiCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .story {
      font-size: var(--font-size-lg);
      line-height: 1.5;
      margin: 0;
    }
    dl {
      display: grid;
      gap: var(--space-1);
      margin: 0;
      padding: var(--space-3) var(--space-4);
      background: var(--color-surface-subtle);
      border-radius: var(--radius-sm);
    }
    dt {
      font-weight: var(--font-weight-bold);
    }
    dd {
      margin: 0;
    }
  `,
  template: `<ui-card data-final-screen="MISSION">
    <h2 ui-card-header>The mission</h2>
    <p class="story">{{ story() }}</p>
    <dl>
      <dt>Equipment</dt>
      <dd>{{ equipment() }}</dd>
    </dl>
    @if (error(); as text) {
      <ui-alert variant="error">{{ text }}</ui-alert>
    }
    <ui-button size="lg" [fullWidth]="true" [loading]="sending()" (click)="start()">Start case</ui-button>
  </ui-card>`,
})
export class EventFinalMission {
  private readonly eventApi = inject(EventApi);

  readonly story = input.required<string>();
  readonly equipment = input.required<string>();
  readonly changed = output<void>();

  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  protected async start(): Promise<void> {
    if (this.sending()) return;
    this.sending.set(true);
    this.error.set(null);
    try {
      await this.eventApi.startCase();
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.sending.set(false);
      this.changed.emit();
    }
  }
}
