import { ChangeDetectionStrategy, Component } from "@angular/core";
import { SentinelBrief } from "../../sentinel/sentinel-brief";

interface ReferenceSection {
  title: string;
  /** Plain lines, "$$ " display formulas and four-space data lines: the same text format SENTINEL's briefs use. */
  text: string;
}

/** The ML page of the participant guide's quick reference: syntax and formulas only. */
const SECTIONS: readonly ReferenceSection[] = [
  {
    title: "Confusion matrix",
    text: [
      "    Predicted positive   Predicted negative",
      "    Actual positive:   TP   FN",
      "    Actual negative:   FP   TN",
    ].join("\n"),
  },
  {
    title: "Classification threshold",
    text: [
      "$$ Probability >= Threshold → Positive",
      "$$ Probability < Threshold → Negative",
      "Example: probability 0.72 and threshold 0.60 give Positive.",
    ].join("\n"),
  },
  { title: "Accuracy", text: "$$ Accuracy = (TP + TN) / (TP + TN + FP + FN)" },
  { title: "Precision", text: "$$ Precision = TP / (TP + FP)" },
  { title: "Recall", text: "$$ Recall = TP / (TP + FN)" },
  { title: "F1 score", text: "$$ F1 = 2 x Precision x Recall / (Precision + Recall)" },
  { title: "Standardization", text: "$$ z = (x - mean) / std" },
  { title: "Mean", text: "$$ Mean = Sum of values / Number of values" },
  {
    title: "Median",
    text: [
      "Odd count: the middle value.",
      "    1  3  4  7  9      median 4",
      "Even count: the average of the two middle values.",
      "$$ (3 + 7) / 2",
      "    1  3  7  9",
    ].join("\n"),
  },
  {
    title: "Quick metrics reminder",
    text: "Accuracy, Precision, Recall, F1, False Positives, False Negatives. Do not assume the highest single metric automatically means the best model.",
  },
];

/** A collapsible "Quick reference" (a native details element, so it is keyboard and screen-reader friendly). */
@Component({
  selector: "event-recovery-reference",
  imports: [SentinelBrief],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    details {
      background: var(--color-surface);
      border: var(--border-card);
      border-radius: var(--radius-card);
      box-shadow: var(--shadow-card);
    }
    summary {
      display: flex;
      align-items: center;
      min-block-size: 48px;
      padding: 0 var(--space-5);
      font-weight: var(--font-weight-bold);
      cursor: pointer;
    }
    summary:focus-visible {
      outline: 2px solid var(--color-border-focus);
      outline-offset: -2px;
      border-radius: var(--radius-card);
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
      gap: var(--space-4);
      padding: 0 var(--space-5) var(--space-5);
    }
    section {
      display: grid;
      gap: var(--space-1);
      align-content: start;
      min-inline-size: 0;
    }
    h3 {
      margin: 0;
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
  `,
  template: `<details data-recovery-reference>
    <summary>Quick reference</summary>
    <div class="grid">
      @for (section of sections; track section.title) {
        <section>
          <h3>{{ section.title }}</h3>
          <sentinel-brief [brief]="section.text" />
        </section>
      }
    </div>
  </details>`,
})
export class EventRecoveryReference {
  protected readonly sections = SECTIONS;
}
