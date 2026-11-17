import { ChangeDetectionStrategy, Component, computed, input } from "@angular/core";
import { TeamFinal } from "../../../../core/event/event-models";
import { FINAL_STAGE_LABELS, formatTime } from "../../../../core/event/format";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";

/**
 * The last screen: team, status and time, the suspect the team chose and its Final Investigation Report, read-only (its
 * own, never another team's). It never says whether anything was correct.
 */
@Component({
  selector: "event-final-submitted",
  imports: [UiCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .lead {
      margin: 0;
      font-size: var(--font-size-lg);
      font-weight: var(--font-weight-bold);
    }
    dl {
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: var(--space-1) var(--space-4);
      margin: 0;
    }
    dt {
      color: var(--color-text-muted);
    }
    dd {
      margin: 0;
      font-weight: var(--font-weight-semibold);
    }
    .report {
      display: grid;
      gap: var(--space-3);
      margin-block-start: var(--space-4);
    }
    .report h3 {
      margin: 0;
      font-size: var(--font-size-base);
    }
    .report p {
      margin: 0;
    }
    .report__label {
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-bold);
      letter-spacing: 0.06em;
      color: var(--color-text-muted);
    }
    .report__text {
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
  `,
  template: `<ui-card data-final-screen="SUBMITTED">
    <h2 ui-card-header>Submitted</h2>
    <p class="lead" data-final-message>{{ submitted().message }}</p>
    <dl>
      <dt>Team</dt>
      <dd>{{ submitted().teamName }}</dd>
      <dt>Status</dt>
      <dd>{{ status() }}</dd>
      <dt>Time</dt>
      <dd class="event-num">{{ time() }}</dd>
      @if (submitted().suspectId; as id) {
        <dt>Your answer</dt>
        <dd data-submitted-suspect>{{ id }} — {{ submitted().suspectName ?? id }}</dd>
      }
    </dl>
    @if (submitted().suspectId) {
      <section class="report" data-submitted-report aria-label="Your Final Investigation Report">
        <h3>Your Final Investigation Report</h3>
        @for (part of parts(); track part.label) {
          <div>
            <p class="report__label">{{ part.label }}</p>
            <p class="report__text">{{ part.text }}</p>
          </div>
        } @empty {
          <p class="report__text" data-report-empty>No report was submitted.</p>
        }
      </section>
    }
  </ui-card>`,
})
export class EventFinalSubmitted {
  readonly submitted = input.required<NonNullable<TeamFinal["submission"]>>();

  protected readonly status = computed(() => FINAL_STAGE_LABELS[this.submitted().status]);
  protected readonly time = computed(() => formatTime(this.submitted().at));
  /** The filled boxes of the team's report, in order. */
  protected readonly parts = computed(() => {
    const report = this.submitted().report;
    if (!report) return [];
    return [
      { label: "EVIDENCE 1", text: report.evidence1 },
      { label: "EVIDENCE 2", text: report.evidence2 },
      { label: "EVIDENCE 3", text: report.evidence3 },
      { label: "EVIDENCE 4", text: report.evidence4 },
      { label: "EXPLANATION", text: report.explanation },
    ].filter((part): part is { label: string; text: string } => part.text !== null);
  });
}
