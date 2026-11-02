import { ChangeDetectionStrategy, Component, input } from "@angular/core";
import { RouterLink } from "@angular/router";
import { AdminAlert } from "../../../../core/event/event-models";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";

/** Setup problems reported by the board; each links to the page that fixes it. */
@Component({
  selector: "app-alerts-list",
  imports: [RouterLink, UiCard, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<ui-card>
    <div ui-card-header class="event-inline">
      <h2 id="alerts-heading">Alerts</h2>
      @if (alerts().length) {
        <ui-status-chip variant="warning">{{ alerts().length }}</ui-status-chip>
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
    } @else {
      <p class="event-muted">No alerts. The event setup is complete.</p>
    }
  </ui-card>`,
  styles: `
    h2 {
      margin: 0;
      font-size: var(--font-size-lg);
    }
    ul {
      display: grid;
      gap: var(--space-2);
      margin: 0;
      padding-left: var(--space-5);
    }
    p {
      margin: 0;
    }
  `,
})
export class AlertsList {
  readonly alerts = input.required<readonly AdminAlert[]>();
}
