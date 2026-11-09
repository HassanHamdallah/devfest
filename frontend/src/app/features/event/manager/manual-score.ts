import { ChangeDetectionStrategy, Component, inject, input, linkedSignal, output, signal } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { message } from "../../../core/api";
import { EventApi } from "../../../core/event/event-api";
import { ToastService } from "../../../shared/ui/toast/toast.service";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiInput } from "../../../shared/ui/ui-input/ui-input";

/** The room's own 0-15 score for one team, for a room without an automatic score. Audited on the server. */
@Component({
  selector: "event-manual-score",
  imports: [FormsModule, UiAlert, UiButton, UiInput],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    form {
      display: grid;
      gap: var(--space-2);
    }
  `,
  template: `<form (ngSubmit)="save()" data-manual-score>
    <ui-input
      label="Room score (0 to 15)"
      name="score"
      type="number"
      [compact]="true"
      [hint]="'Typed by you; hints and cheating are taken off automatically.'"
      [ngModel]="draft()"
      (ngModelChange)="draft.set($event)"
    />
    @if (error(); as text) {
      <ui-alert variant="error" [dismissible]="true">{{ text }}</ui-alert>
    }
    <div class="event-inline">
      <ui-button type="submit" size="sm" variant="secondary" [loading]="saving()">Save score</ui-button>
      @if (score() === null) {
        <span class="event-muted">No score entered yet.</span>
      }
    </div>
  </form>`,
})
export class EventManualScore {
  private readonly eventApi = inject(EventApi);
  private readonly toasts = inject(ToastService);

  readonly roomId = input.required<number>();
  readonly teamId = input.required<number>();
  readonly score = input.required<number | null>();
  readonly changed = output<void>();

  protected readonly draft = linkedSignal<number | null, string>({
    source: this.score,
    computation: (score) => (score === null ? "" : String(score)),
  });
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected async save(): Promise<void> {
    const text = String(this.draft()).trim();
    const value = Number(text);
    if (text === "" || Number.isNaN(value) || value < 0 || value > 15) {
      this.error.set("Enter a score from 0 to 15.");
      return;
    }
    if (this.saving()) return;
    this.saving.set(true);
    this.error.set(null);
    try {
      await this.eventApi.saveManualScore(this.roomId(), this.teamId(), value);
      this.toasts.show("Room score saved");
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.saving.set(false);
      this.changed.emit();
    }
  }
}
