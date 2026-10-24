import {
  ChangeDetectionStrategy,
  Component,
  input,
  output,
} from "@angular/core";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";

/** A failed background refresh: the last loaded data stays on screen, with a way to try again. */
@Component({
  selector: "event-refresh-error",
  imports: [UiAlert, UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: "event-inline" },
  template: `<ui-alert variant="warning"
      >Could not refresh: {{ error() }} Showing the last loaded data.</ui-alert
    >
    <ui-button variant="secondary" size="sm" (click)="retry.emit()"
      >Try again</ui-button
    >`,
})
export class EventRefreshError {
  readonly error = input.required<string>();
  readonly retry = output<void>();
}
