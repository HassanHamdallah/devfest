import { ChangeDetectionStrategy, Component, inject, input, output, signal } from "@angular/core";
import { message } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import { FinalSuspect } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";

/** Screen 10: choose ONE of the six suspects. Confirmation first; the answer can be given one time only. */
@Component({
  selector: "event-final-suspects",
  imports: [UiAlert, UiButton, UiCard, UiConfirmDialog],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    fieldset {
      display: grid;
      gap: var(--space-2);
      margin: 0;
      padding: 0;
      border: 0;
    }
    legend {
      padding: 0;
      margin-block-end: var(--space-2);
    }
    label {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      min-block-size: 52px;
      padding: 0 var(--space-4);
      cursor: pointer;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-sm);
    }
    label:has(input:checked) {
      background: var(--color-primary-subtle);
      border: var(--border-outline-thin);
    }
    input {
      inline-size: 20px;
      block-size: 20px;
    }
    .actions {
      display: grid;
      gap: var(--space-3);
      margin-block-start: var(--space-3);
    }
  `,
  template: `<ui-card data-final-screen="SUSPECTS">
    <h2 ui-card-header>Who is responsible?</h2>
    <fieldset>
      <legend>Choose one suspect. You can answer only once.</legend>
      @for (suspect of suspects(); track suspect.id) {
        <label [attr.data-suspect]="suspect.id">
          <input
            type="radio"
            name="suspect"
            [value]="suspect.id"
            [checked]="chosen()?.id === suspect.id"
            (change)="chosen.set(suspect)"
          />
          {{ suspect.name }}
        </label>
      }
    </fieldset>
    <div class="actions">
      @if (error(); as text) {
        <ui-alert variant="error" [dismissible]="true">{{ text }}</ui-alert>
      }
      <ui-button size="lg" [fullWidth]="true" [disabled]="chosen() === null" (click)="confirming.set(true)"
        >Submit final answer</ui-button
      >
    </div>
    <ui-confirm-dialog
      [open]="confirming()"
      title="Submit your final answer?"
      [message]="'You name ' + (chosen()?.name ?? '') + '. This can be done only once and cannot be changed.'"
      confirmLabel="Submit final answer"
      [busy]="sending()"
      (confirmed)="submit()"
      (cancelled)="confirming.set(false)"
    />
  </ui-card>`,
})
export class EventFinalSuspects {
  private readonly eventApi = inject(EventApi);

  readonly suspects = input.required<readonly FinalSuspect[]>();
  readonly changed = output<void>();

  protected readonly chosen = signal<FinalSuspect | null>(null);
  protected readonly confirming = signal(false);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  protected async submit(): Promise<void> {
    const suspect = this.chosen();
    if (!suspect || this.sending()) return;
    this.sending.set(true);
    this.error.set(null);
    try {
      await this.eventApi.submitSuspect(suspect.id);
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.sending.set(false);
      this.confirming.set(false);
      this.changed.emit();
    }
  }
}
