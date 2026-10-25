import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  input,
} from "@angular/core";

/**
 * The GDG On Campus · Birzeit University logo (served from /brand, so it works offline). `ring` adds the
 * logo's four-colour ring around it again as an outer halo — blue top-left, green top-right, yellow
 * bottom-right, red bottom-left — for the sign-in page.
 */
@Component({
  selector: "app-logo",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    "[class.app-logo--ring]": "ring()",
    "[style.--logo-size.px]": "size()",
  },
  template: `<img
    [src]="source()"
    [width]="size()"
    [height]="size()"
    alt="GDG On Campus · Birzeit University"
    decoding="async"
  />`,
  styles: `
    :host {
      display: inline-grid;
      place-items: center;
      flex-shrink: 0;
      border-radius: 50%;
    }
    img {
      display: block;
      inline-size: var(--logo-size);
      block-size: var(--logo-size);
      border-radius: 50%;
    }
    :host(.app-logo--ring) {
      padding: 10px;
      background:
        linear-gradient(var(--color-background), var(--color-background))
          padding-box,
        conic-gradient(
            var(--brand-green) 0 25%,
            var(--brand-yellow) 0 50%,
            var(--brand-red) 0 75%,
            var(--brand-blue) 0 100%
          )
          border-box;
      border: 6px solid transparent;
    }
  `,
})
export class AppLogo {
  /** Rendered width and height in CSS pixels. */
  readonly size = input(48);
  readonly ring = input(false, { transform: booleanAttribute });

  /** The 256 px copy is plenty below that size; the original is used for large renderings. */
  protected readonly source = computed(() =>
    this.size() > 128
      ? "brand/gdg-birzeit-logo.jpg"
      : "brand/gdg-birzeit-logo-256.jpg",
  );
}
