import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from "@angular/core";

/**
 * Thin capsule progress bar (role="progressbar"). The fill uses --progress-color when the parent sets it
 * (e.g. a room colour), the brand blue otherwise. `label` is its accessible name.
 */
@Component({
  selector: "ui-progress",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    role: "progressbar",
    "aria-valuemin": "0",
    "[attr.aria-valuemax]": "max()",
    "[attr.aria-valuenow]": "value()",
    "[attr.aria-label]": "label()",
  },
  template: `<span class="ui-progress__fill" [style.width.%]="percent()"></span>`,
  styles: `
    :host {
      display: block;
      block-size: 10px;
      overflow: hidden;
      background: var(--color-surface-subtle);
      border: 1px solid var(--color-outline);
      border-radius: var(--radius-full);
    }
    .ui-progress__fill {
      display: block;
      block-size: 100%;
      background: var(--progress-color, var(--brand-blue));
      transition: width var(--duration-base) var(--ease-out);
    }
  `,
})
export class UiProgress {
  readonly value = input.required<number>();
  readonly max = input.required<number>();
  readonly label = input.required<string>();

  protected readonly percent = computed(() => {
    const max = this.max();
    return max > 0 ? Math.min(100, Math.max(0, (this.value() / max) * 100)) : 0;
  });
}
