import { ChangeDetectionStrategy, Component, input } from "@angular/core";

export type UiStatusChipVariant =
  "success" | "danger" | "warning" | "accent" | "neutral";

/**
 * Short status label: success (Passed, Correct, Collected), danger (Failed, Incorrect),
 * warning (Pending, Closed, Locked), accent (Now), neutral (everything else).
 */
@Component({
  selector: "ui-status-chip",
  template: "<ng-content />",
  styleUrl: "./ui-status-chip.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    "[class]": "'ui-status-chip-host ui-status-chip-host--' + variant()",
  },
})
export class UiStatusChip {
  readonly variant = input<UiStatusChipVariant>("neutral");
}
