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
import { DeductionView } from "../../../core/event/event-models";
import { formatTime } from "../../../core/event/format";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiInput } from "../../../shared/ui/ui-input/ui-input";

const REASON_MAX_CHARACTERS = 500;

/**
 * One line of the append-only deduction log. Rows are never deleted; "Cancel" asks for a reason, keeps the row in the
 * log, and restores the hint or the points.
 */
@Component({
  selector: "event-deduction-item",
  imports: [FormsModule, UiAlert, UiButton, UiInput],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    "[class.cancelled]": "deduction().cancelled",
    "[attr.data-deduction]": "deduction().kind",
    "[attr.data-cancelled]": "deduction().cancelled",
  },
  styles: `
    :host {
      display: grid;
      gap: var(--space-1);
      padding: var(--space-2) 0;
      border-top: 1px solid var(--color-border);
    }
    :host(.cancelled) .entry {
      color: var(--color-text-muted);
      text-decoration: line-through;
    }
    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
    }
    .text {
      display: grid;
      gap: 0;
      min-inline-size: 0;
    }
    .title {
      min-block-size: 0;
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
  template: `<div class="row">
      <div class="text">
        <div class="event-inline title">
          <strong class="entry">{{ title() }}</strong>
          @if (showTeam()) {
            <span class="event-muted">{{ deduction().teamName }} · {{ deduction().roomCode }}</span>
          }
        </div>
        @if (deduction().reason) {
          <span class="detail entry">{{ deduction().reason }}</span>
        }
        <span class="detail event-muted">{{ createdLine() }}</span>
        @if (cancelledLine(); as line) {
          <span class="detail" data-cancelled-line>{{ line }}</span>
        }
      </div>
      @if (canCancel() && !deduction().cancelled && !formOpen()) {
        <ui-button size="sm" variant="ghost" (click)="openForm()"
          >Cancel {{ deduction().kind === "HINT" ? "hint" : "cheating" }}</ui-button
        >
      }
    </div>
    @if (formOpen()) {
      <form (ngSubmit)="cancel()">
        <ui-input label="Cancellation reason" name="reason" [compact]="true" [required]="true" [(ngModel)]="reason" />
        @if (error(); as text) {
          <ui-alert variant="error">{{ text }}</ui-alert>
        }
        <div class="event-inline">
          <ui-button type="submit" size="sm" variant="danger" [loading]="saving()">Confirm cancellation</ui-button>
          <ui-button size="sm" variant="ghost" [disabled]="saving()" (click)="closeForm()">Keep it</ui-button>
        </div>
      </form>
    }`,
})
export class EventDeductionItem {
  private readonly eventApi = inject(EventApi);

  readonly deduction = input.required<DeductionView>();
  /** The log page also names the team and the room. */
  readonly showTeam = input(false);
  /** False on read-only lists. */
  readonly canCancel = input(true);
  readonly changed = output<void>();

  protected readonly formOpen = signal(false);
  protected readonly reason = signal("");
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly title = computed(() =>
    this.deduction().kind === "HINT" ? "Hint · −2 points" : "Cheating · −7.5 points",
  );
  protected readonly createdLine = computed(() => {
    const { createdByName, createdAt } = this.deduction();
    return `Recorded by ${createdByName} at ${formatTime(createdAt)}`;
  });
  protected readonly cancelledLine = computed(() => {
    const d = this.deduction();
    if (!d.cancelled) return null;
    return `Cancelled by ${d.cancelledByName ?? "—"} at ${formatTime(d.cancelledAt)}: ${d.cancelReason ?? ""}`;
  });

  protected openForm(): void {
    this.formOpen.set(true);
    this.error.set(null);
  }

  protected closeForm(): void {
    this.formOpen.set(false);
    this.reason.set("");
  }

  protected async cancel(): Promise<void> {
    const reason = this.reason().trim();
    const problem = !reason
      ? "Enter a reason for cancelling this deduction."
      : reason.length > REASON_MAX_CHARACTERS
        ? `Cancellation reason is too long (max ${REASON_MAX_CHARACTERS}).`
        : null;
    if (problem || this.saving()) {
      this.error.set(problem);
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    try {
      await this.eventApi.cancelDeduction(this.deduction().id, reason);
      this.closeForm();
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.saving.set(false);
      // Also after a failure: "already cancelled" means the board is stale.
      this.changed.emit();
    }
  }
}
