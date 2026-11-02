import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from "@angular/core";

/** Bar widths repeat in this order so a placeholder reads like uneven lines of text. */
const LINE_WIDTHS = ["92%", "78%", "86%", "64%", "72%"];

/**
 * Placeholder shown while data loads, shaped like the content that will replace it: a heading bar,
 * then `lines` text bars, optionally inside `cards` card outlines. Purely visual (aria-hidden); the
 * caller announces the loading state itself.
 */
@Component({
  selector: "ui-skeleton",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "aria-hidden": "true" },
  template: `@for (card of cardList(); track card) {
    <div class="ui-skeleton__card">
      <span class="event-skeleton ui-skeleton__title"></span>
      @for (width of widths(); track $index) {
        <span class="event-skeleton ui-skeleton__line" [style.width]="width"></span>
      }
    </div>
  }`,
  styles: `
    :host {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 260px), 1fr));
      gap: var(--space-4);
    }
    .ui-skeleton__card {
      display: grid;
      gap: var(--space-3);
      padding: var(--space-5);
      background: var(--color-surface);
      border: var(--border-card);
      border-radius: var(--radius-card);
    }
    .ui-skeleton__title {
      width: 40%;
      height: 1.25rem;
    }
    .ui-skeleton__line {
      height: 0.8rem;
    }
  `,
})
export class UiSkeleton {
  readonly cards = input(1);
  readonly lines = input(3);

  protected readonly cardList = computed(() =>
    Array.from({ length: this.cards() }, (_, index) => index),
  );
  protected readonly widths = computed(() =>
    Array.from(
      { length: this.lines() },
      (_, index) => LINE_WIDTHS[index % LINE_WIDTHS.length],
    ),
  );
}
