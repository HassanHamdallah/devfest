import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from "@angular/core";
import { UiAlert } from "../ui-alert/ui-alert";
import { UiButton } from "../ui-button/ui-button";
import { UiEmptyState } from "../ui-empty-state/ui-empty-state";
import { UiSpinner } from "../ui-spinner/ui-spinner";

/**
 * Loading / error / empty block for a page's main data. Precedence: loading, then error, then empty;
 * it renders nothing otherwise, so the page shows its own content next to it under its own @if.
 */
@Component({
  selector: "ui-page-state",
  imports: [UiAlert, UiButton, UiEmptyState, UiSpinner],
  templateUrl: "./ui-page-state.html",
  styleUrl: "./ui-page-state.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "[hidden]": "!visible()" },
})
export class UiPageState {
  readonly loading = input(false);
  readonly loadingLabel = input("Loading…");
  readonly error = input<string | null>(null);
  readonly empty = input(false);
  readonly emptyTitle = input("Nothing here yet.");
  readonly emptyDescription = input<string | null>(null);

  /** "Try again" was pressed; the page reloads its data. */
  readonly retry = output<void>();

  protected readonly visible = computed(
    () => this.loading() || !!this.error() || this.empty(),
  );
}
