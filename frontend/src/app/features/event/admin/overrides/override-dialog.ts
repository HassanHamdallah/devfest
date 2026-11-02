import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  output,
  signal,
  untracked,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButtonVariant } from "../../../../shared/ui/ui-button/ui-button";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiTextarea } from "../../../../shared/ui/ui-textarea/ui-textarea";

/**
 * Confirmation of an admin override: states the exact consequence, asks for the audited reason (3–500
 * characters) and shows the server's message when it is refused. Extra fields are projected above the reason.
 */
@Component({
  selector: "app-override-dialog",
  imports: [FormsModule, UiAlert, UiConfirmDialog, UiTextarea],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<ui-confirm-dialog
    [open]="open()"
    [title]="title()"
    [message]="consequence()"
    [confirmVariant]="variant()"
    [busy]="busy()"
    [confirmDisabled]="confirmDisabled()"
    (confirmed)="confirm()"
    (cancelled)="cancelled.emit()"
  >
    <div class="override-dialog">
      <ng-content />
      <ui-textarea
        label="Reason"
        name="reason"
        [rows]="2"
        hint="3 to 500 characters. Recorded in the audit log."
        [error]="reasonError()"
        [ngModel]="reason()"
        (ngModelChange)="reason.set($event)"
      />
      @if (error(); as text) {
        <ui-alert variant="error">{{ text }}</ui-alert>
      }
    </div>
  </ui-confirm-dialog>`,
  styles: `
    .override-dialog {
      display: grid;
      gap: var(--space-3);
    }
  `,
})
export class OverrideDialog {
  readonly open = input(false);
  readonly title = input.required<string>();
  /** The exact consequence in plain words. */
  readonly consequence = input.required<string>();
  readonly variant = input<UiButtonVariant>("primary");
  readonly busy = input(false);
  readonly error = input<string | null>(null);
  readonly confirmDisabled = input(false);

  /** Emits the trimmed reason. */
  readonly confirmed = output<string>();
  readonly cancelled = output<void>();

  protected readonly reason = signal("");
  private readonly touched = signal(false);
  protected readonly reasonError = computed(() => {
    if (!this.touched()) return null;
    const length = this.reason().trim().length;
    return length < 3 || length > 500
      ? "Give a reason (3 to 500 characters)."
      : null;
  });

  constructor() {
    effect(() => {
      if (!this.open()) return;
      untracked(() => {
        this.reason.set("");
        this.touched.set(false);
      });
    });
  }

  protected confirm(): void {
    this.touched.set(true);
    if (this.reasonError()) return;
    this.confirmed.emit(this.reason().trim());
  }
}
