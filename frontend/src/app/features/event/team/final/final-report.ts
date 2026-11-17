import { ChangeDetectionStrategy, Component, input } from "@angular/core";
import { FinalCaseClue } from "../../../../core/event/event-models";

/** A decrypted report shown in place: monospace, line breaks kept, long lines wrapped. */
@Component({
  selector: "event-final-report",
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
      min-inline-size: 0;
    }
    section {
      display: grid;
      gap: var(--space-2);
      padding: var(--space-3);
      background: var(--color-surface);
      border: var(--border-card);
      border-radius: var(--radius-md);
    }
    header {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
    }
    h3 {
      margin: 0;
      font-family: var(--font-mono);
      font-size: var(--font-size-sm);
      overflow-wrap: anywhere;
    }
    pre {
      max-block-size: 24rem;
      margin: 0;
      overflow: auto;
      font-family: var(--font-mono);
      font-size: var(--font-size-sm);
      line-height: 1.5;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }
  `,
  template: `<section data-report-viewer [attr.aria-label]="clue().title + '.txt'">
    <header>
      <h3>{{ clue().title }}.txt</h3>
    </header>
    <pre data-report-text tabindex="0">{{ clue().report }}</pre>
  </section>`,
})
export class EventFinalReport {
  readonly clue = input.required<FinalCaseClue>();
}
