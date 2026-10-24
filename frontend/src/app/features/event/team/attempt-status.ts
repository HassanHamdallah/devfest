import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from "@angular/core";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { PendingAttempt } from "./pending-attempt";

/** Error of a PendingAttempt, with "Retry" while an unconfirmed attempt is stored. */
@Component({
  selector: "event-attempt-status",
  imports: [UiAlert, UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      gap: var(--space-2);
      justify-items: start;
    }
    :host:empty {
      display: none;
    }
  `,
  template: `@if (attempt().unconfirmed()) {
      <ui-alert variant="warning">{{ retryText() }}</ui-alert>
      <ui-button
        variant="secondary"
        [loading]="attempt().sending()"
        (click)="retry.emit()"
        >Retry</ui-button
      >
    } @else if (attempt().error(); as text) {
      <ui-alert variant="error">{{ text }}</ui-alert>
    }`,
})
export class EventAttemptStatus {
  readonly attempt = input.required<PendingAttempt<unknown, unknown>>();
  readonly retry = output<void>();

  protected readonly retryText = computed(
    () => this.attempt().error() ?? "Sending the same attempt again…",
  );
}
