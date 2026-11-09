import { ChangeDetectionStrategy, Component, input, output, signal } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { RECOVERY_PATH } from "../../../../core/event/event-api";
import { RecoveryRequest, RecoveryResult } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { EventAttemptStatus } from "../attempt-status";
import { PendingAttempt } from "../pending-attempt";

const ANSWER_MAX = 500;

/** The one Recovery Challenge: unlimited attempts, no penalty. A correct answer collects the missing clue. */
@Component({
  selector: "event-final-recovery",
  imports: [EventAttemptStatus, FormsModule, UiAlert, UiButton, UiCard, UiInput],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    form {
      display: grid;
      gap: var(--space-3);
    }
    blockquote {
      margin: 0;
      padding: var(--space-3) var(--space-4);
      white-space: pre-wrap;
      overflow-wrap: anywhere;
      background: var(--color-surface-subtle);
      border-radius: var(--radius-sm);
    }
  `,
  template: `<ui-card data-final-screen="RECOVERY">
    <h2 ui-card-header>Recovery Challenge</h2>
    <form (ngSubmit)="submit()">
      <p>
        Your team has 3 of 4 clues. Solve this challenge to collect the missing clue and join the case with the time
        that is left.
      </p>
      <blockquote data-recovery-question>{{ recovery().question }}</blockquote>
      <p class="event-muted">Wrong answers cost nothing. Attempts so far: {{ recovery().attempts }}.</p>
      <ui-input label="Recovery answer" name="answer" [required]="true" [(ngModel)]="answer" />
      @if (feedback(); as text) {
        <ui-alert variant="error" [dismissible]="true">{{ text }}</ui-alert>
      }
      <event-attempt-status [attempt]="attempt" (retry)="retry()" />
      <ui-button type="submit" size="lg" [fullWidth]="true" [loading]="attempt.sending()"
        >Submit recovery answer</ui-button
      >
    </form>
  </ui-card>`,
})
export class EventFinalRecovery {
  readonly recovery = input.required<{ question: string; attempts: number }>();
  readonly changed = output<void>();

  protected readonly attempt = new PendingAttempt<RecoveryRequest, RecoveryResult>("recovery");
  protected readonly answer = signal("");
  protected readonly feedback = signal<string | null>(null);

  protected async submit(): Promise<void> {
    const answer = this.answer().trim();
    this.feedback.set(!answer ? "Enter your recovery answer." : answer.length > ANSWER_MAX ? `Recovery answer is too long (max ${ANSWER_MAX} characters).` : null);
    if (this.feedback()) return;
    this.finish(await this.attempt.submit(RECOVERY_PATH, { answer }));
  }

  protected async retry(): Promise<void> {
    this.finish(await this.attempt.retry());
  }

  private finish(result: RecoveryResult | null): void {
    if (result && !result.correct) this.feedback.set("Not correct — try again.");
    this.changed.emit();
  }
}
