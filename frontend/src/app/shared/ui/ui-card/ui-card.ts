import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  input,
} from "@angular/core";

/**
 * Padded surface. Project a header/footer with the `ui-card-header` /
 * `ui-card-footer` attributes; omit either and its wrapper collapses.
 */
@Component({
  selector: "ui-card",
  templateUrl: "./ui-card.html",
  styleUrl: "./ui-card.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "[class.ui-card-host--fill]": "fill()" },
})
export class UiCard {
  /**
   * Stretch the card's visible surface to the full height of its container. A grid or
   * flex row stretches the `<ui-card>` host, but the surface is an `<article>` inside
   * it that only grows with its content — so two cards side by side end at different
   * heights. Opt in with `<ui-card fill>` where cards must line up. Off by default so
   * no existing card changes.
   */
  readonly fill = input(false, { transform: booleanAttribute });
}
