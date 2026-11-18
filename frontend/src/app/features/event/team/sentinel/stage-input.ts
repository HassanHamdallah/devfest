import { ChangeDetectionStrategy, Component, computed, signal } from "@angular/core";
import { SentinelStage } from "../../../../core/event/event-models";
import { SentinelBrief } from "./sentinel-brief";
import { InputData, dataOf } from "./sentinel-data";
import { SentinelFigure } from "./sentinel-figure";
import { parseNumberAnswer } from "./sentinel-logic";
import { SentinelStageBase } from "./stage-base";
import { SentinelStageShell } from "./stage-shell";

/** What a team is told when a NUMBER answer is empty or is not a plain decimal (the server says the same). */
export const NUMBER_HINT = "Enter a number, for example 0.5.";

/**
 * A typed answer stage (stages 6, 8 and 9): one answer only, at most 40 characters. The team types it; a NUMBER stage
 * accepts a plain decimal with a point or a comma. An empty or unparsable number is not sent (Submit stays off and says
 * so), so it never uses up the one answer. The confirmation shows exactly what was typed. The server judges the
 * answer; like every stage this one then shows only "Saved".
 */
@Component({
  selector: "sentinel-stage-input",
  imports: [SentinelBrief, SentinelFigure, SentinelStageShell],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./sentinel-shared.css",
  styles: `
    .answer {
      display: grid;
      gap: var(--space-1);
      max-inline-size: 360px;
    }
    .answer label {
      font-weight: var(--font-weight-semibold);
    }
    .answer input {
      min-block-size: 48px;
      padding: var(--space-2) var(--space-3);
      font: inherit;
      font-family: var(--font-mono);
      font-size: var(--font-size-lg);
      font-variant-numeric: tabular-nums;
      color: var(--color-text);
      background: var(--color-surface);
      border: var(--border-card);
      border-radius: var(--radius-md);
    }
    .answer input:focus-visible {
      outline: none;
      border: var(--border-outline-thin);
      box-shadow: 0 0 0 4px var(--color-focus-ring);
    }
    .answer input:disabled {
      opacity: 0.6;
    }
    .answer__count {
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
  `,
  template: `
    <sentinel-stage-shell
      [stage]="shellStage()"
      [ready]="ready()"
      [readOnly]="readOnly()"
      [submitting]="submitting()"
      [error]="error()"
      submitLabel="Submit answer"
      [confirmTitle]="'Submit ' + typed() + '? One answer only.'"
      [hint]="hint()"
      (confirmed)="send()"
    >
      <div class="stage">
        @if (data().figure; as figure) {
          <sentinel-figure [figure]="figure" />
        }
        <sentinel-brief data-input-brief [brief]="stage().brief" />
        <div class="answer">
          <label [for]="'answer-' + stage().number">Your answer</label>
          <input
            type="text"
            [id]="'answer-' + stage().number"
            data-answer
            autocomplete="off"
            autocapitalize="off"
            spellcheck="false"
            [attr.inputmode]="data().inputKind === 'NUMBER' ? 'decimal' : 'text'"
            [attr.maxlength]="data().maxLength"
            [disabled]="readOnly()"
            [value]="value()"
            (input)="value.set($any($event.target).value)"
            (keydown.enter)="enter($event)"
          />
          <span class="answer__count event-num">{{ value().length }} / {{ data().maxLength }}</span>
        </div>
      </div>
    </sentinel-stage-shell>
  `,
})
export class SentinelStageInput extends SentinelStageBase {
  protected readonly data = computed(() => ({ maxLength: 40, inputKind: "TEXT", ...dataOf<Partial<InputData>>(this.stage()) }) as InputData);
  protected readonly value = signal("");

  /** The shell shows the brief as one paragraph; this stage shows it line by line, so the shell gets none. */
  protected readonly shellStage = computed<SentinelStage>(() => ({ ...this.stage(), brief: null }));
  protected readonly typed = computed(() => this.value().trim());
  /** A TEXT answer needs something typed; a NUMBER answer needs a plain decimal number. */
  protected readonly ready = computed(() => this.typed() !== "" && (this.data().inputKind !== "NUMBER" || parseNumberAnswer(this.typed()) !== null));
  protected readonly hint = computed(() => {
    if (this.ready()) return null;
    if (this.data().inputKind === "NUMBER") return NUMBER_HINT;
    return "Type your answer.";
  });

  /** Enter in the box never submits by itself: the confirmation button does, so the answer is always double-checked. */
  protected enter(event: Event): void {
    event.preventDefault();
  }

  protected send(): void {
    if (this.ready()) void this.submit({ answer: this.typed() });
  }
}
