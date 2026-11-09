import { ChangeDetectionStrategy, Component, computed, input } from "@angular/core";
import { TeamFinal } from "../../../../core/event/event-models";
import { FINAL_STAGE_LABELS, formatTime } from "../../../../core/event/format";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";

/** The last screen: team, status and time. It never says whether anything was correct. */
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
    </dl>
  </ui-card>`,
})
export class EventFinalSubmitted {
  readonly submitted = input.required<NonNullable<TeamFinal["submitted"]>>();

  protected readonly status = computed(() => FINAL_STAGE_LABELS[this.submitted().status]);
  protected readonly time = computed(() => formatTime(this.submitted().at));
}
