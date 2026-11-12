import { ChangeDetectionStrategy, Component, computed, input } from "@angular/core";
import { RouterLink } from "@angular/router";
import { AdminAlert } from "../../../../core/event/event-models";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";

/** The board alerts that also stop the final from opening (the same gaps the open-final check names). */
const FINAL_GAP_CODES = ["MISSING_FINAL_CONTENT", "MISSING_CLUE_KEY"];

export function hasFinalGap(alerts: readonly AdminAlert[]): boolean {
  return alerts.some((alert) => FINAL_GAP_CODES.includes(alert.code));
}

/**
 * Setup problems reported by the board; each links to the page that fixes it. With no alerts it shrinks to
 * one line with a zero badge. A blocking alert (an AI mission room that cannot run) is marked: the timer then
 * starts only with "Start anyway".
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
    @if (finalRefused() && hasGap()) {
      <p class="alerts__refused" role="alert" data-final-refused>
        The final cannot open yet. Fix the items marked "Needed for the final": each one links to the page that fixes it.
      </p>
    }
    @if (alerts().length) {
      <ul aria-labelledby="alerts-heading">
        @for (alert of alerts(); track alert.code + alert.message) {
          <li [attr.data-alert]="alert.code" [attr.data-blocking]="alert.blocking || null">
            @if (alert.blocking) {
              <ui-status-chip variant="danger">Blocks the timer</ui-status-chip>
            }
            @if (finalRefused() && isGap(alert)) {
              <ui-status-chip variant="warning">Needed for the final</ui-status-chip>
            }
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
    .alerts__refused {
      font-weight: var(--font-weight-semibold);
    }
    li[data-blocking] {
      font-weight: var(--font-weight-semibold);
    }
    li ui-status-chip {
      margin-inline-end: var(--space-2);
    }
  `,
})
export class AlertsList {
  readonly alerts = input.required<readonly AdminAlert[]>();
  /** Opening the final was just refused because of gaps listed here. */
  readonly finalRefused = input(false);

  protected readonly hasGap = computed(() => hasFinalGap(this.alerts()));

  protected isGap(alert: AdminAlert): boolean {
    return FINAL_GAP_CODES.includes(alert.code);
  }
}
