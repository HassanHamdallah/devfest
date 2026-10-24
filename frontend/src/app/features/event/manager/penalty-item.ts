import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { message } from "../../../core/api";
import { EventApi } from "../../../core/event/event-api";
import { PenaltyView } from "../../../core/event/event-models";
import {
  PENALTY_KIND_LABELS,
  formatPenaltyMinutes,
  formatTime,
} from "../../../core/event/format";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiInput } from "../../../shared/ui/ui-input/ui-input";

const REASON_MAX_CHARACTERS = 500;

/** One penalty line. Penalties are never deleted; "Void" asks for a reason and keeps the entry visible. */
@Component({
  selector: "event-penalty-item",
  imports: [FormsModule, UiAlert, UiButton, UiInput],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "[class.voided]": "penalty().voided" },
  styles: `
    :host {
      display: grid;
      gap: var(--space-1);
      padding: var(--space-2) 0;
      border-top: 1px solid var(--color-border);
    }
    :host(.voided) .entry {
      color: var(--color-text-muted);
      text-decoration: line-through;
    }
    .detail {
      font-size: var(--font-size-sm);
      overflow-wrap: anywhere;
    }
    form {
      display: grid;
      gap: var(--space-2);
    }
  `,
  template: `<div class="event-inline">
      <strong class="entry">{{ title() }}</strong>
      @if (!penalty().voided && !formOpen()) {
        <ui-button size="sm" variant="ghost" (click)="openForm()"
          >Void</ui-button
        >
      }
    </div>
    @if (penalty().note) {
      <span class="detail entry">{{ penalty().note }}</span>
    }
    <span class="detail event-muted">{{ createdLine() }}</span>
    @if (voidedLine(); as line) {
      <span class="detail">{{ line }}</span>
    }
    @if (formOpen()) {
      <form (ngSubmit)="voidPenalty()">
        <ui-input
          label="Void reason"
          name="reason"
          [compact]="true"
          [required]="true"
          [(ngModel)]="reason"
        />
        @if (error(); as text) {
          <ui-alert variant="error">{{ text }}</ui-alert>
        }
        <div class="event-inline">
          <ui-button
            type="submit"
            size="sm"
            variant="danger"
            [loading]="saving()"
            >Void penalty</ui-button
          >
          <ui-button
            size="sm"
            variant="ghost"
            [disabled]="saving()"
            (click)="closeForm()"
            >Cancel</ui-button
          >
        </div>
      </form>
    }`,
})
export class EventPenaltyItem {
  private readonly eventApi = inject(EventApi);

  readonly penalty = input.required<PenaltyView>();
  readonly changed = output<void>();

  protected readonly formOpen = signal(false);
  protected readonly reason = signal("");
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly title = computed(() => {
    const { kind, minutes } = this.penalty();
    return `${PENALTY_KIND_LABELS[kind]} · ${formatPenaltyMinutes(minutes)}`;
  });
  protected readonly createdLine = computed(() => {
    const { createdByName, createdAt } = this.penalty();
    return `Added by ${createdByName} at ${formatTime(createdAt)}`;
  });
  protected readonly voidedLine = computed(() => {
    const p = this.penalty();
    if (!p.voided) return null;
    return `Voided by ${p.voidedByName ?? "—"} at ${formatTime(p.voidedAt)}: ${p.voidReason ?? ""}`;
  });

  protected openForm(): void {
    this.formOpen.set(true);
    this.error.set(null);
  }

  protected closeForm(): void {
    this.formOpen.set(false);
    this.reason.set("");
  }

  protected async voidPenalty(): Promise<void> {
    const reason = this.reason().trim();
    const problem = validateReason(reason);
    if (problem || this.saving()) {
      this.error.set(problem);
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    try {
      await this.eventApi.voidPenalty(this.penalty().id, reason);
      this.closeForm();
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.saving.set(false);
      // Also after a failure: "already voided" means the board is stale.
      this.changed.emit();
    }
  }
}

function validateReason(reason: string): string | null {
  if (!reason) return "Enter a reason for voiding this penalty.";
  return reason.length > REASON_MAX_CHARACTERS
    ? `Void reason is too long (max ${REASON_MAX_CHARACTERS}).`
    : null;
}
