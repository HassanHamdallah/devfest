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
import { UiSkeleton } from "../ui-skeleton/ui-skeleton";

/**
 * Loading / error / empty block for a page's main data. Precedence: loading (a skeleton of the content,
 * never a spinner on a blank page), then error with "Try again", then empty; it renders nothing otherwise,
 * so the page shows its own content next to it under its own @if.
 */
@Component({
  selector: "ui-page-state",
  imports: [UiAlert, UiButton, UiEmptyState, UiSkeleton],
  templateUrl: "./ui-page-state.html",
  styleUrl: "./ui-page-state.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "[hidden]": "!visible()" },
})
export class UiPageState {
  readonly loading = input(false);
  readonly loadingLabel = input("Loading…");
  /** How many card placeholders the skeleton shows while loading. */
  readonly skeletonCards = input(3);
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
