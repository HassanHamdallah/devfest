import { ChangeDetectionStrategy, Component, computed, input } from "@angular/core";
import { parseBlocks } from "./sentinel-text";

/**
 * The text of a brief or a field note: paragraph lines, display formulas (lines that start with "$$ ") and the
 * data lines (four spaces) as one tidy monospaced block. Everything is printed through interpolation, so the text is
 * escaped; the shapes come from `parseBlocks` / `formatFormula`, which are pure and unit tested.
 */
@Component({
  selector: "sentinel-brief",
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      gap: var(--space-1);
      max-inline-size: 70ch;
      line-height: 1.6;
    }
    p,
    pre {
      margin: 0;
    }
    p {
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }
    .spaced {
      margin-block-start: var(--space-2);
    }
    /* A display formula: serif maths stack, on its own line, indented a little. */
    .formula {
      padding-inline: var(--formula-indent, var(--space-4));
      font-family: "Cambria Math", "STIX Two Math", "Latin Modern Math", "Times New Roman", Georgia, serif;
      font-size: var(--formula-size, 1.15em);
      line-height: 1.5;
      white-space: normal;
      overflow-wrap: break-word;
      font-variant-numeric: lining-nums;
    }
    .formula i {
      font-style: italic;
      padding-inline-end: 0.06em;
    }
    .formula sub {
      font-size: 0.7em;
      line-height: 0;
    }
    /* One block for all the data lines of the text. */
    .data {
      max-inline-size: 100%;
      padding: var(--space-3) var(--space-4);
      overflow-x: auto;
      font-family: var(--font-mono);
      font-size: var(--font-size-sm);
      line-height: 1.5;
      font-variant-numeric: tabular-nums;
      background: var(--color-surface-subtle);
      border-radius: var(--radius-md);
      tab-size: 4;
    }
  `,
  template: `@for (block of blocks(); track $index) {
    @switch (block.kind) {
      @case ("formula") {
        <p class="formula" role="math" [attr.aria-label]="block.plain" [class.spaced]="block.spaced" data-formula>
          @for (token of block.tokens; track $index) {
            @switch (token.kind) {
              @case ("var") {
                <i>{{ token.text }}</i>
              }
              @case ("fn") {
                <span>{{ token.text }}</span>
              }
              @case ("sub") {
                <sub>{{ token.text }}</sub>
              }
              @default {
                <span>{{ token.text }}</span>
              }
            }
          }
        </p>
      }
      @case ("data") {
        <pre class="data" [class.spaced]="block.spaced" data-data-block>{{ block.text }}</pre>
      }
      @default {
        <p [class.spaced]="block.spaced">{{ block.text }}</p>
      }
    }
  }`,
})
export class SentinelBrief {
  readonly brief = input<string | null>(null);
  protected readonly blocks = computed(() => parseBlocks(this.brief()));
}
