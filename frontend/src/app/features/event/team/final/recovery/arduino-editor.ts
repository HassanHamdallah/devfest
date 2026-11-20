import { NgTemplateOutlet } from "@angular/common";
import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, input, output } from "@angular/core";
import { ArduinoView } from "../../../../../core/event/event-models";
import { EventCodeText } from "./arduino-code";
import { SyntaxToken, longestLine, tokenizeLine } from "./arduino-syntax";

/** The editor is at least this many characters wide, so a short program still looks like a code window. */
const MIN_WIDTH_CH = 56;
/** Room after the longest line, so the line being typed never scrolls inside its own input. */
const SPARE_CH = 3;

/**
 * The program as numbered lines: each line is its own single-line monospace input (so no line can be added or removed),
 * drawn over a coloured copy of the same text. Tab and Shift+Tab move between the lines; Enter and the up and down arrows do
 * too. The missing-behaviour choice is a select at its place in the program, with the chosen option shown as code below it.
 * Everything is controlled by the parent: this only reports the edits.
 */
@Component({
  selector: "event-arduino-editor",
  imports: [EventCodeText, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
      min-inline-size: 0;
    }
    /* The editor scrolls sideways inside its own box; the page never does. */
    .scroller {
      max-inline-size: 100%;
      overflow-x: auto;
      background: var(--color-surface);
      border: var(--border-outline-thin);
      border-radius: var(--radius-md);
    }
    .scroller:focus-within {
      border-color: var(--color-border-focus);
    }
    .editor {
      --gutter: 3.2rem;
      inline-size: calc(var(--gutter) + var(--editor-ch) * 1ch + 2 * var(--space-3));
      min-inline-size: 100%;
      padding-block: var(--space-2);
      font-family: var(--font-mono);
      font-size: var(--font-size-sm);
      line-height: 1.6;
    }
    .row {
      display: flex;
      align-items: stretch;
    }
    .row:focus-within {
      background: var(--color-primary-subtle);
    }
    .num {
      flex: none;
      inline-size: var(--gutter);
      padding-inline-end: var(--space-3);
      text-align: end;
      color: var(--color-text-muted);
      font-variant-numeric: tabular-nums;
      user-select: none;
    }
    .cell {
      position: relative;
      flex: none;
      inline-size: calc(var(--editor-ch) * 1ch + 2 * var(--space-3));
    }
    .hl,
    .line {
      box-sizing: border-box;
      margin: 0;
      padding: 0 var(--space-3);
      font: inherit;
      letter-spacing: 0;
      white-space: pre;
      tab-size: 2;
    }
    .hl {
      position: absolute;
      inset: 0;
      overflow: hidden;
      pointer-events: none;
    }
    .line {
      position: relative;
      display: block;
      inline-size: 100%;
      block-size: 100%;
      color: transparent;
      caret-color: var(--color-text);
      background: transparent;
      border: 0;
      border-radius: 0;
      outline: 0;
    }
    .line::selection {
      background: color-mix(in srgb, var(--color-primary) 30%, transparent);
    }
    .comment {
      color: var(--color-text-muted);
      font-style: italic;
    }
    .keyword {
      color: var(--brand-blue-ink);
      font-weight: var(--font-weight-semibold);
    }
    .string {
      color: var(--brand-green-ink);
    }
    .number {
      color: var(--brand-red-ink);
    }
    /* The one place where the team chooses code instead of typing it. */
    .slot {
      display: flex;
      align-items: flex-start;
      margin-block: var(--space-2);
      padding-block: var(--space-2);
      background: var(--color-warning-subtle);
      border-block: 1px dashed var(--color-warning);
    }
    .slot__body {
      display: grid;
      flex: none;
      gap: var(--space-2);
      inline-size: calc(var(--editor-ch) * 1ch + 2 * var(--space-3));
      padding-inline: var(--space-3);
      font-family: var(--font-family-base);
    }
    .slot__prompt {
      font-family: var(--font-mono);
      font-size: var(--font-size-sm);
      font-style: italic;
      line-height: 1.5;
      color: var(--color-warning-text);
      white-space: normal;
    }
    select {
      inline-size: 100%;
      min-block-size: 44px;
      padding: 0 var(--space-3);
      font: inherit;
      font-family: var(--font-mono);
      font-size: var(--font-size-sm);
      color: var(--color-text);
      background: var(--color-surface);
      border: var(--border-outline-thin);
      border-radius: var(--radius-md);
    }
    select:focus-visible {
      outline: 2px solid var(--color-border-focus);
      outline-offset: 2px;
    }
  `,
  template: `<div class="scroller" data-arduino-scroller>
    <div class="editor" role="group" [attr.aria-label]="'Program ' + program().fileName" [style.--editor-ch]="widthCh()" data-arduino-editor>
      @if (program().slot.afterLine === 0) {
        <ng-container *ngTemplateOutlet="slotRow" />
      }
      @for (line of lines(); track $index; let i = $index) {
        <div class="row" [attr.data-row]="i + 1">
          <span class="num" aria-hidden="true">{{ i + 1 }}</span>
          <div class="cell">
            <span class="hl" aria-hidden="true">@for (token of highlighted()[i]; track $index) {<span [class]="token.kind">{{ token.text }}</span>}</span>
            <input
              class="line"
              type="text"
              spellcheck="false"
              autocomplete="off"
              autocapitalize="off"
              autocorrect="off"
              maxlength="300"
              data-editor-control
              [attr.data-line]="i + 1"
              [attr.aria-label]="'Line ' + (i + 1)"
              [value]="line"
              (input)="edited(i, $event)"
              (keydown)="keyed($event, i)"
            />
          </div>
        </div>
        @if (program().slot.afterLine === i + 1) {
          <ng-container *ngTemplateOutlet="slotRow" />
        }
      }
    </div>
  </div>
  <ng-template #slotRow>
    <div class="slot" data-arduino-slot>
      <span class="num" aria-hidden="true">▸</span>
      <div class="slot__body">
        <label class="slot__prompt" for="arduino-slot">{{ program().slot.prompt }}</label>
        <select id="arduino-slot" [value]="option() ?? ''" (change)="chose($event)" data-arduino-option>
          <option value="" [selected]="option() === null">Choose the missing behaviour…</option>
          @for (choice of program().slot.options; track choice.letter) {
            <option [value]="choice.letter" [selected]="option() === choice.letter">{{ choice.letter }}) {{ oneLine(choice.code) }}</option>
          }
        </select>
        @if (chosenCode(); as code) {
          <event-code-text [code]="code" data-arduino-chosen />
        }
      </div>
    </div>
  </ng-template>`,
})
export class EventArduinoEditor {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly program = input.required<ArduinoView>();
  /** The current text of every line, in order. */
  readonly lines = input.required<readonly string[]>();
  /** The letter of the chosen missing-behaviour option; null while none is chosen. */
  readonly option = input<string | null>(null);

  readonly lineChange = output<{ index: number; value: string }>();
  readonly optionChange = output<string | null>();

  protected readonly highlighted = computed<SyntaxToken[][]>(() => this.lines().map(tokenizeLine));
  protected readonly widthCh = computed(() => Math.max(MIN_WIDTH_CH, longestLine(this.lines()) + SPARE_CH));
  protected readonly chosenCode = computed(() => this.program().slot.options.find((choice) => choice.letter === this.option())?.code ?? null);

  /** An option's code on one line, for the select's closed face (a select cannot show a line break). */
  protected oneLine(code: string): string {
    return code.split("\n").join("  ");
  }

  protected edited(index: number, event: Event): void {
    // An input holds one line: a pasted line break is already dropped by the browser.
    this.lineChange.emit({ index, value: (event.target as HTMLInputElement).value });
  }

  protected chose(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.optionChange.emit(value === "" ? null : value);
  }

  /** Enter and the arrows move between the lines; Tab and Shift+Tab are the browser's own. */
  protected keyed(event: KeyboardEvent, index: number): void {
    if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
    const delta = event.key === "ArrowUp" ? -1 : event.key === "ArrowDown" || event.key === "Enter" ? 1 : 0;
    if (delta === 0) return;
    const target = this.host.nativeElement.querySelector<HTMLInputElement>(`input[data-line="${index + 1 + delta}"]`);
    event.preventDefault();
    target?.focus();
  }
}
