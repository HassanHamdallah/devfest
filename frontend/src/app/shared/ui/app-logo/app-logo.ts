import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from "@angular/core";

/**
 * The GDG On Campus · Birzeit University logo, served from /brand so it works offline. The image already
 * carries the four-colour ring (blue top-left, green top-right, yellow bottom-right, red bottom-left).
 */
@Component({
  selector: "app-logo",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "[style.--logo-size.px]": "size()" },
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
  `,
})
export class AppLogo {
  /** Rendered width and height in CSS pixels. */
  readonly size = input(48);

  /** The 256 px copy is plenty below that size; the original is used for large renderings. */
  protected readonly source = computed(() =>
    this.size() > 128
      ? "brand/gdg-birzeit-logo.jpg"
      : "brand/gdg-birzeit-logo-256.jpg",
  );
}
