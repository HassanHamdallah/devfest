import { ChangeDetectionStrategy, Component, input } from "@angular/core";

import { UiSpinner } from "../ui-spinner/ui-spinner";

export type UiButtonVariant = "primary" | "secondary" | "ghost" | "danger";
export type UiButtonSize = "sm" | "md" | "lg";
export type UiButtonType = "button" | "submit";

/**
 * Presentational button. Never call HttpClient or a router from here — a feature
 * component wires the click/submit behavior.
 */
@Component({
  selector: "ui-button",
  imports: [UiSpinner],
  templateUrl: "./ui-button.html",
  styleUrl: "./ui-button.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    "[class.ui-button-host--full-width]": "fullWidth()",
  },
})
export class UiButton {
  readonly variant = input<UiButtonVariant>("primary");
  readonly size = input<UiButtonSize>("md");
  readonly type = input<UiButtonType>("button");
  readonly loading = input(false);
  readonly disabled = input(false);
  readonly fullWidth = input(false);
  /**
   * Toggle state for buttons that stay "on" after being pressed (the theme
   * switch, say). Left null the button is a plain action and gets no
   * aria-pressed at all, which is the right default.
   */
  readonly pressed = input<boolean | null>(null);

  protected get isDisabled(): boolean {
    return this.disabled() || this.loading();
  }
}
