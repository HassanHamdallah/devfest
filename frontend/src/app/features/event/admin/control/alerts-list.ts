import { ChangeDetectionStrategy, Component, input } from "@angular/core";
import { RouterLink } from "@angular/router";
import { AdminAlert } from "../../../../core/event/event-models";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";

/**
 * Setup problems reported by the board; each links to the page that fixes it. With no alerts it shrinks to
 * one line with a zero badge.
 */
@Component({
  selector: "app-alerts-list",
  imports: [RouterLink, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section
    class="event-panel alerts"
    [class.alerts--empty]="!alerts().length"
    aria-labelledby="alerts-heading"
  >
    <div class="alerts__head">
      <h2 id="alerts-heading">Alerts</h2>
      <ui-status-chip [variant]="alerts().length ? 'warning' : 'success'">{{
        alerts().length
      }}</ui-status-chip>
      @if (!alerts().length) {
        <p class="event-muted">No alerts. The event setup is complete.</p>
      }
    </div>
    @if (alerts().length) {
      <ul aria-labelledby="alerts-heading">
        @for (alert of alerts(); track alert.code + alert.message) {
          <li [attr.data-alert]="alert.code">
            <a [routerLink]="alert.link">{{ alert.message }}</a>
          </li>
        }
      </ul>
    }
  </section>`,
  styles: `
    .alerts {
      display: grid;
      gap: var(--space-3);
      padding: var(--space-4) var(--space-5);
      border-inline-start: 6px solid var(--brand-yellow);
    }
    .alerts--empty {
      padding-block: var(--space-3);
      border-inline-start-color: var(--brand-green);
    }
    .alerts__head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2) var(--space-3);
    }
    h2 {
      margin: 0;
      font-size: var(--font-size-base);
    }
    ul {
      display: grid;
      gap: var(--space-2);
      margin: 0;
      padding-left: var(--space-5);
    }
    p {
      margin: 0;
      font-size: var(--font-size-sm);
    }
  `,
})
export class AlertsList {
  readonly alerts = input.required<readonly AdminAlert[]>();
}
