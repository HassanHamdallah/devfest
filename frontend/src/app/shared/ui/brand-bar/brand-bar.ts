import { ChangeDetectionStrategy, Component } from "@angular/core";

/** The four-colour strip from the GDG logo (blue, red, yellow, green). Decorative only. */
@Component({
  selector: "app-brand-bar",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "aria-hidden": "true" },
  template: "",
  styles: `
    :host {
      display: block;
      block-size: 4px;
      background: linear-gradient(
        90deg,
        var(--brand-blue) 0 25%,
        var(--brand-red) 0 50%,
        var(--brand-yellow) 0 75%,
        var(--brand-green) 0 100%
      );
    }
  `,
})
export class BrandBar {}
