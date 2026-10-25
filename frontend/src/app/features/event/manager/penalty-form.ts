import {
  ChangeDetectionStrategy,
  Component,
  inject,
  input,
  output,
  signal,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { message } from "../../../core/api";
import { EventApi } from "../../../core/event/event-api";
import { PenaltyKind } from "../../../core/event/event-models";
import {
  MANUAL_PENALTY_KINDS,
  PENALTY_KIND_LABELS,
  PENALTY_KIND_MINUTES,
} from "../../../core/event/format";
import { ToastService } from "../../../shared/ui/toast/toast.service";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiInput } from "../../../shared/ui/ui-input/ui-input";
import {
  UiSelect,
  UiSelectOption,
} from "../../../shared/ui/ui-select/ui-select";

const NOTE_MAX_CHARACTERS = 500;

/** "Hint (+2 min)" etc.: the minutes are fixed per kind, so the option says what will be added. */
const KIND_OPTIONS: readonly UiSelectOption[] = MANUAL_PENALTY_KINDS.map(
  (kind) => ({
    value: kind,
    label: `${PENALTY_KIND_LABELS[kind]} (+${PENALTY_KIND_MINUTES[kind]} min)`,
  }),
);

/** Adds a manual time penalty for one team in this room. */
@Component({
  selector: "event-penalty-form",
  imports: [FormsModule, UiAlert, UiButton, UiInput, UiSelect],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    form {
      display: grid;
      gap: var(--space-2);
    }
  `,
  template: `<form (ngSubmit)="add()">
    <ui-select
      label="Penalty kind"
      name="kind"
      [compact]="true"
      [options]="kindOptions"
      [(ngModel)]="kind"
    />
    <ui-input
      label="Penalty note"
      name="note"
      [compact]="true"
      [(ngModel)]="note"
    />
    @if (error(); as text) {
      <ui-alert variant="error" [dismissible]="true">{{ text }}</ui-alert>
    }
    <div class="event-inline">
      <ui-button
        type="submit"
        size="sm"
        variant="secondary"
        [loading]="saving()"
        >Add penalty</ui-button
      >
      @if (saved()) {
        <span class="event-muted" aria-live="polite">Penalty added.</span>
      }
    </div>
  </form>`,
})
export class EventPenaltyForm {
  private readonly eventApi = inject(EventApi);
  private readonly toasts = inject(ToastService);

  readonly roomId = input.required<number>();
  readonly teamId = input.required<number>();
  readonly changed = output<void>();

  protected readonly kindOptions = KIND_OPTIONS;
  protected readonly kind = signal<PenaltyKind>("HINT");
  protected readonly note = signal("");
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly error = signal<string | null>(null);

  protected async add(): Promise<void> {
    if (this.saving()) return;
    const note = this.note().trim();
    if (note.length > NOTE_MAX_CHARACTERS) {
      this.error.set(`Penalty note is too long (max ${NOTE_MAX_CHARACTERS}).`);
      return;
    }
    this.saving.set(true);
    this.saved.set(false);
    this.error.set(null);
    try {
      await this.eventApi.addPenalty(this.roomId(), {
        teamId: this.teamId(),
        kind: this.kind(),
        note,
      });
      this.note.set("");
      this.saved.set(true);
      this.toasts.show(`${PENALTY_KIND_LABELS[this.kind()]} penalty added`);
      this.changed.emit();
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.saving.set(false);
    }
  }
}
