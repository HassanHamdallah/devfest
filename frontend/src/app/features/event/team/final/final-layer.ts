import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { layerPath } from "../../../../core/event/event-api";
import { FinalActionResult, LayerAnswerRequest, TeamFinal } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { EventAttemptStatus } from "../attempt-status";
import { PendingAttempt } from "../pending-attempt";
import { FinalWait } from "./final-wait";

const WHOLE_NUMBER = /^[0-9]{1,12}$/;

/** Screens 3 to 9: one layer shows only its ciphertext C and modulus n and takes one integer. */
@Component({
  selector: "event-final-layer",
  imports: [EventAttemptStatus, FormsModule, UiAlert, UiButton, UiCard, UiInput],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    form {
      display: grid;
      gap: var(--space-3);
    }
    .help {
      margin: var(--space-3) 0;
      color: var(--color-text-muted);
    }
    .numbers {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--space-3);
      margin: 0;
    }
    .numbers div {
      display: grid;
      gap: var(--space-1);
      padding: var(--space-3) var(--space-4);
      background: var(--color-surface-subtle);
      border-inline-start: 6px solid var(--color-primary);
      border-radius: var(--radius-sm);
    }
    dt {
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    dd {
      margin: 0;
      font-family: var(--font-mono, monospace);
      font-size: var(--font-size-xl, 1.75rem);
      font-weight: var(--font-weight-bold);
      overflow-wrap: anywhere;
    }
  `,
  template: `<ui-card data-final-screen="LAYER" [attr.data-layer]="layer().layer">
    <h2 ui-card-header>Layer {{ layer().layer }} of 4</h2>
    <dl class="numbers">
      <div>
        <dt>Ciphertext C</dt>
        <dd data-layer-c>{{ layer().c }}</dd>
      </div>
      <div>
        <dt>Modulus n</dt>
        <dd data-layer-n>{{ layer().n }}</dd>
      </div>
    </dl>
    <p class="help">Find the key card where p × q = n, then M = C^d mod n.</p>
    <form (ngSubmit)="submit()">
      <ui-input
        label="Decrypted number M"
        name="value"
        type="text"
        [required]="true"
        [(ngModel)]="value"
      />
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
        >{{ wait.left() > 0 ? "Wait " + wait.left() + " s" : "Try this number" }}</ui-button
      >
    </form>
  </ui-card>`,
})
export class EventFinalLayer {
  readonly layer = input.required<NonNullable<TeamFinal["layer"]>>();
  readonly waitSeconds = input(0);
  readonly changed = output<void>();

  protected readonly attempt = new PendingAttempt<LayerAnswerRequest, FinalActionResult>("layer");
  protected readonly wait = new FinalWait(inject(DestroyRef));
  protected readonly layerNumber = computed(() => this.layer().layer);
  protected readonly value = signal("");
  protected readonly feedback = signal<string | null>(null);

  constructor() {
    effect(() => this.wait.set(this.waitSeconds()));
    // A new layer starts clean: no leftover message or number from the previous one.
    effect(() => {
      // The number is a computed value, so a reload that returns the same layer does not clear the message.
      this.layerNumber();
      untracked(() => {
        this.feedback.set(null);
        this.value.set("");
      });
    });
  }

  protected async submit(): Promise<void> {
    if (this.wait.left() > 0) return;
    const value = this.value().trim();
    if (!WHOLE_NUMBER.test(value)) {
      this.feedback.set("Enter a whole number.");
      return;
    }
    this.feedback.set(null);
    this.finish(await this.attempt.submit(layerPath(this.layer().layer), { value }));
  }

  protected async retry(): Promise<void> {
    this.finish(await this.attempt.retry());
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
