import { ChangeDetectionStrategy, Component, input } from "@angular/core";

export type UiBadgeVariant =
  "neutral" | "primary" | "success" | "warning" | "danger";

@Component({
  selector: "ui-badge",
  templateUrl: "./ui-badge.html",
  styleUrl: "./ui-badge.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    "[class]": "'ui-badge-host ui-badge-host--' + variant()",
  },
})
export class UiBadge {
  readonly variant = input<UiBadgeVariant>("neutral");
}
