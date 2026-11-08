import { ChangeDetectionStrategy, Component, input, output, signal } from "@angular/core";
import { SentinelStage } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";

/**
 * The frame every stage shares: the Field note and instructions on top, the stage's own screen in the middle, and
 * the one Submit button at the bottom, which asks first ("it cannot be changed afterwards"). The stage decides
 * when it is `ready`; the shell never knows an answer.
 */
@Component({
  selector: "sentinel-stage-shell",
  imports: [UiAlert, UiButton, UiConfirmDialog],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./sentinel-shared.css",
  host: { class: "shell" },
  template: `
    <header class="shell__intro">
      @if (stage().fieldNote; as note) {
        <p class="field-note" data-field-note>{{ note }}</p>
      }
      @if (stage().brief; as brief) {
        <p class="shell__brief">{{ brief }}</p>
      }
    </header>
    <div class="shell__body">
      <ng-content />
    </div>
    @if (error(); as text) {
      <ui-alert variant="error">{{ text }}</ui-alert>
    }
    <footer class="shell__actions">
      <ui-button
        size="lg"
        [disabled]="!ready() || readOnly() || submitting()"
        [loading]="submitting()"
        (click)="confirming.set(true)"
        data-submit-stage
        >{{ submitLabel() }}</ui-button
      >
      @if (readOnly()) {
        <span class="event-muted">Input is frozen right now.</span>
      } @else if (hint(); as text) {
        <span class="event-muted">{{ text }}</span>
      }
    </footer>
    <ui-confirm-dialog
      [open]="confirming()"
      [title]="confirmTitle() ?? submitLabel() + '?'"
      message="Once submitted this stage is locked and cannot be changed."
      [busy]="submitting()"
      (cancelled)="confirming.set(false)"
      (confirmed)="confirm()"
    />
  `,
})
export class SentinelStageShell {
  readonly stage = input.required<SentinelStage>();
  readonly ready = input(false);
  readonly readOnly = input(false);
  readonly submitting = input(false);
  readonly error = input<string | null>(null);
  readonly submitLabel = input("Submit stage");
  readonly hint = input<string | null>(null);
  /** Replaces the question of the confirmation (used when the answer should be double-checked). */
  readonly confirmTitle = input<string | null>(null);
  readonly confirmed = output<void>();

  protected readonly confirming = signal(false);

  protected confirm(): void {
    this.confirming.set(false);
    this.confirmed.emit();
  }
}
