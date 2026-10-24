import { ChangeDetectionStrategy, Component, input } from "@angular/core";
import { OverrideOutcome } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";

/** What the last override did (role="status"), with its non-blocking warnings. */
@Component({
  selector: "app-override-outcome",
  imports: [UiAlert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<p class="override-outcome" role="status">{{
      outcome()?.message ?? ""
    }}</p>
    @if (outcome()?.warnings?.length) {
      <ui-alert variant="warning">
        <ul>
          @for (warning of outcome()?.warnings; track warning) {
            <li>{{ warning }}</li>
          }
        </ul>
      </ui-alert>
    }`,
  styles: `
    :host {
      display: grid;
      gap: var(--space-2);
    }
    .override-outcome {
      margin: 0;
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    .override-outcome:empty {
      display: none;
    }
    ul {
      margin: 0;
      padding-left: var(--space-5);
    }
  `,
})
export class OverrideOutcomeView {
  readonly outcome = input<OverrideOutcome | null>(null);
}
