import { ChangeDetectionStrategy, Component, computed, input } from "@angular/core";
import { SyntaxToken, tokenizeLine } from "./arduino-syntax";

/**
 * A read-only block of Arduino code with the basic colouring ({@link tokenizeLine}); the text is printed through
 * interpolation, so it is always escaped. Used by the quick reference and the missing-behaviour preview.
 */
@Component({
  selector: "event-code-text",
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
      min-inline-size: 0;
    }
    pre {
      margin: 0;
      padding: var(--space-3) var(--space-4);
      overflow-x: auto;
      font-family: var(--font-mono);
      font-size: var(--font-size-sm);
      line-height: 1.5;
      tab-size: 2;
      background: var(--color-surface-subtle);
      border: var(--border-outline-thin);
      border-radius: var(--radius-md);
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
  `,
  template: `<pre data-code-text>@for (line of lines(); track $index) {<span>@for (token of line; track $index) {<span [class]="token.kind">{{ token.text }}</span>}</span>
}</pre>`,
})
export class EventCodeText {
  readonly code = input.required<string>();

  protected readonly lines = computed<SyntaxToken[][]>(() => this.code().split("\n").map(tokenizeLine));
}
