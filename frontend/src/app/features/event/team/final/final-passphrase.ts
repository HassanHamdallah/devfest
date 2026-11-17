import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, output, signal } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { passphrasePath } from "../../../../core/event/event-api";
import { FinalActionResult, FinalCaseClue, PassphraseRequest } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { EventAttemptStatus } from "../attempt-status";
import { PendingAttempt } from "../pending-attempt";
import { FinalWait } from "./final-wait";

const MAX_PASSPHRASE = 200;

/**
 * "Recovered passphrase" + Check for one evidence file. Sent with an Idempotency-Key (kept for a retry); the server
 * trims and lower-cases it, so a pasted line ending does not matter. A wrong one says only that it does not open the
 * clue; five wrong in a row show the server's 30-second wait, counted down here.
 */
@Component({
  selector: "event-final-passphrase",
  imports: [EventAttemptStatus, FormsModule, UiAlert, UiButton, UiInput],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      gap: var(--space-2);
    }
    form {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: end;
      gap: var(--space-2);
    }
    @media (max-width: 480px) {
      form {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
  template: `<form (ngSubmit)="submit()" [attr.data-passphrase-form]="clue().number">
      <ui-input
        label="Recovered passphrase"
        [name]="'passphrase-' + clue().number"
        [compact]="true"
        [ngModel]="value()"
        (ngModelChange)="value.set($event)"
      />
      <ui-button type="submit" [loading]="attempt().sending()" [disabled]="wait.left() > 0" data-passphrase-check>{{
        wait.left() > 0 ? "Wait " + wait.left() + " s" : "Check"
      }}</ui-button>
    </form>
    @if (feedback(); as text) {
      <ui-alert variant="error" data-passphrase-feedback>{{ text }}</ui-alert>
    }
    <event-attempt-status [attempt]="attempt()" (retry)="retry()" />`,
})
export class EventFinalPassphrase {
  readonly clue = input.required<FinalCaseClue>();
  /** One stored attempt per evidence file (created by the parent, kind "clue-N"). */
  readonly attempt = input.required<PendingAttempt<PassphraseRequest, FinalActionResult>>();
  readonly changed = output<void>();

  protected readonly wait = new FinalWait(inject(DestroyRef));
  protected readonly value = signal("");
  protected readonly feedback = signal<string | null>(null);

  constructor() {
    effect(() => this.wait.set(this.clue().waitSeconds));
  }

  protected async submit(): Promise<void> {
    if (this.wait.left() > 0) return;
    const passphrase = this.value();
    if (!passphrase.trim()) {
      this.feedback.set("Paste the passphrase from participant_test/secret_N.txt.");
      return;
    }
    if (passphrase.length > MAX_PASSPHRASE) {
      this.feedback.set(`The passphrase is at most ${MAX_PASSPHRASE} characters.`);
      return;
    }
    this.feedback.set(null);
    this.finish(await this.attempt().submit(passphrasePath(this.clue().number), { passphrase }));
  }

  protected async retry(): Promise<void> {
    this.finish(await this.attempt().retry());
  }

  private finish(result: FinalActionResult | null): void {
    if (result?.accepted) this.value.set("");
    if (result && !result.accepted) {
      this.feedback.set(result.message);
      this.wait.set(result.waitSeconds);
    }
    this.changed.emit();
  }
}
