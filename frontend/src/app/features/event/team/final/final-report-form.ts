import { ChangeDetectionStrategy, Component, model } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiTextarea } from "../../../../shared/ui/ui-textarea/ui-textarea";

/** The most characters one box of the report may hold (the server refuses more). */
export const REPORT_MAX = 600;

/** What the team has typed so far: four pieces of evidence and an explanation. */
export interface ReportDraft {
  evidence1: string;
  evidence2: string;
  evidence3: string;
  evidence4: string;
  explanation: string;
}

export const EMPTY_DRAFT: ReportDraft = { evidence1: "", evidence2: "", evidence3: "", evidence4: "", explanation: "" };

/** True when one of the boxes holds more than the limit (the page then blocks Submit). */
export function isOverLimit(draft: ReportDraft): boolean {
  return Object.values(draft).some((text) => text.length > REPORT_MAX);
}

type Box = keyof ReportDraft;

const EVIDENCE: readonly { key: Box; label: string }[] = [
  { key: "evidence1", label: "Evidence 1" },
  { key: "evidence2", label: "Evidence 2" },
  { key: "evidence3", label: "Evidence 3" },
  { key: "evidence4", label: "Evidence 4" },
];

/**
 * The Final Investigation Report card: four evidence boxes and an optional explanation, each with a character counter.
 * It only edits the draft (a two-way `value`); saving it on the device and submitting it belong to the page.
 */
@Component({
  selector: "event-final-report-form",
  imports: [FormsModule, UiCard, UiTextarea],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
    }
    h2 {
      margin: 0;
      font-size: var(--font-size-lg);
    }
    .intro {
      margin: 0 0 var(--space-4);
    }
    .boxes {
      display: grid;
      gap: var(--space-4);
    }
    .note {
      margin: var(--space-3) 0 0;
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
  `,
  template: `<ui-card data-report-form>
    <h2 ui-card-header>Final Investigation Report</h2>
    <p class="intro">Your answer must include a brief explanation of the evidence supporting your conclusion.</p>
    <div class="boxes">
      @for (box of evidence; track box.key) {
        <ui-textarea
          [label]="box.label"
          [name]="box.key"
          placeholder="Which record, time or contradiction supports your answer?"
          [rows]="3"
          [hint]="counter(box.key)"
          [error]="tooLong(box.key) ? 'At most 600 characters.' : null"
          [ngModel]="value()[box.key]"
          (ngModelChange)="set(box.key, $event)"
        />
      }
      <ui-textarea
        label="Explanation (optional)"
        name="explanation"
        [rows]="4"
        [hint]="counter('explanation')"
        [error]="tooLong('explanation') ? 'At most 600 characters.' : null"
        [ngModel]="value().explanation"
        (ngModelChange)="set('explanation', $event)"
      />
    </div>
    <p class="note" data-report-note>Saved on this device until you submit.</p>
  </ui-card>`,
})
export class EventFinalReportForm {
  readonly value = model.required<ReportDraft>();
  protected readonly evidence = EVIDENCE;

  protected set(box: Box, text: string | null): void {
    this.value.set({ ...this.value(), [box]: text ?? "" });
  }

  protected counter(box: Box): string {
    return `${this.value()[box].length} / ${REPORT_MAX}`;
  }

  protected tooLong(box: Box): boolean {
    return this.value()[box].length > REPORT_MAX;
  }
}
