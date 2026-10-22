import { ChangeDetectionStrategy, Component, input } from "@angular/core";

@Component({
  selector: "ui-empty-state",
  templateUrl: "./ui-empty-state.html",
  styleUrl: "./ui-empty-state.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UiEmptyState {
  readonly title = input.required<string>();
  readonly description = input<string | null>(null);
}
