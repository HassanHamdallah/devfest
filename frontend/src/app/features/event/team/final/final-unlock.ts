import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { UNLOCK_PATH } from "../../../../core/event/event-api";
import { FinalActionResult, RecoveredKey, UnlockRequest } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { EventAttemptStatus } from "../attempt-status";
import { PendingAttempt } from "../pending-attempt";
import { FinalWait } from "./final-wait";
import { EventRecoveryRecap } from "./recovery/recovery-recap";

const CODE_MAX = 500;

/** Screen 1: one input per room, labelled with the real room name. The answer never says which input was wrong. */
@Component({
  selector: "event-final-unlock",
  imports: [EventAttemptStatus, EventRecoveryRecap, FormsModule, UiAlert, UiButton, UiCard, UiInput],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    form {
      display: grid;
      gap: var(--space-3);
    }
    .inputs {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: var(--space-3);
    }
  `,
  template: `<ui-card data-final-screen="UNLOCK">
    <h2 ui-card-header>Open the case</h2>
    <form (ngSubmit)="submit()">
      @if (recoveredKeys().length > 0) {
        <event-recovery-recap [keys]="recoveredKeys()" />
      }
      <p>
        Enter the output you collected in each room. All four must match their own room.
      </p>
      <div class="inputs">
        @for (name of roomNames(); track name; let index = $index) {
          <ui-input
            [label]="name"
            [name]="'code-' + index"
            [ngModel]="codes()[index]"
            (ngModelChange)="setCode(index, $event)"
          />
        }
      </div>
      @if (feedback(); as text) {
        <ui-alert variant="error" data-final-feedback>{{ text }}</ui-alert>
      }
      <event-attempt-status [attempt]="attempt" (retry)="retry()" />
      <ui-button
        type="submit"
        size="lg"
        [fullWidth]="true"
        [loading]="attempt.sending()"
        [disabled]="wait.left() > 0"
        >{{ wait.left() > 0 ? "Wait " + wait.left() + " s" : "Open the case" }}</ui-button
      >
    </form>
  </ui-card>`,
})
export class EventFinalUnlock {
  readonly roomNames = input.required<readonly string[]>();
  /** The keys the team's recovery granted (empty for a team that needed none). */
  readonly recoveredKeys = input<readonly RecoveredKey[]>([]);
  /** The server's wait at the last load; the page reloads after every answer. */
  readonly waitSeconds = input(0);
  readonly changed = output<void>();

  protected readonly attempt = new PendingAttempt<UnlockRequest, FinalActionResult>("unlock");
  protected readonly wait = new FinalWait(inject(DestroyRef));
  protected readonly codes = signal<readonly string[]>(["", "", "", ""]);
  protected readonly feedback = signal<string | null>(null);

  constructor() {
    effect(() => this.wait.set(this.waitSeconds()));
  }

  protected setCode(index: number, value: string): void {
    this.codes.update((codes) => codes.map((code, i) => (i === index ? value : code)));
  }

  protected async submit(): Promise<void> {
    if (this.wait.left() > 0) return;
    const codes = this.codes().map((code) => code.trim());
    if (codes.some((code) => code.length > CODE_MAX)) {
      this.feedback.set(`Each output is at most ${CODE_MAX} characters.`);
      return;
    }
    this.feedback.set(null);
    this.finish(await this.attempt.submit(UNLOCK_PATH, { codes }));
  }

  protected async retry(): Promise<void> {
    this.finish(await this.attempt.retry());
  }

  private finish(result: FinalActionResult | null): void {
    if (result && !result.accepted) {
      this.feedback.set(result.message);
      this.wait.set(result.waitSeconds);
    }
    this.changed.emit();
  }
}
