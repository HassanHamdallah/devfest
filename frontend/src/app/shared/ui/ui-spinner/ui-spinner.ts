import { ChangeDetectionStrategy, Component, input } from "@angular/core";

export type UiSpinnerSize = "sm" | "md" | "lg";

@Component({
  selector: "ui-spinner",
  templateUrl: "./ui-spinner.html",
  styleUrl: "./ui-spinner.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    role: "status",
    "[attr.aria-busy]": "true",
    "[class.ui-spinner-host--sm]": "size() === 'sm'",
    "[class.ui-spinner-host--md]": "size() === 'md'",
    "[class.ui-spinner-host--lg]": "size() === 'lg'",
  },
})
export class UiSpinner {
  readonly size = input<UiSpinnerSize>("md");
}
