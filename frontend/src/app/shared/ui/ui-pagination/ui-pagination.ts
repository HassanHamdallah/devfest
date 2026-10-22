import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from "@angular/core";

import { UiButton } from "../ui-button/ui-button";

/**
 * Previous/Next pager with a compact page indicator ("Page 2 of 7 · 134
 * results") — deliberately no numbered page-link strip, which is far more
 * layout and accessibility surface than a plain list needs. Hidden entirely
 * when there's nothing to page through (`totalPages <= 1`), so a caller can
 * drop it in unconditionally without its own `@if`.
 */
@Component({
  selector: "ui-pagination",
  imports: [UiButton],
  templateUrl: "./ui-pagination.html",
  styleUrl: "./ui-pagination.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    "[hidden]": "totalPages() <= 1",
  },
})
export class UiPagination {
  /** 0-indexed current page. */
  readonly page = input.required<number>();
  readonly totalPages = input.required<number>();
  readonly totalElements = input(0);
  readonly disabled = input(false);

  /** Emits the 0-indexed page to move to — the caller owns re-fetching. */
  readonly pageChange = output<number>();

  protected readonly isFirst = computed(() => this.page() <= 0);
  protected readonly isLast = computed(
    () => this.page() >= this.totalPages() - 1,
  );
  protected readonly summary = computed(() => {
    const total = this.totalElements();
    return `Page ${this.page() + 1} of ${this.totalPages()} · ${total} ${total === 1 ? "result" : "results"}`;
  });

  protected previous(): void {
    if (!this.isFirst() && !this.disabled()) {
      this.pageChange.emit(this.page() - 1);
    }
  }

  protected next(): void {
    if (!this.isLast() && !this.disabled()) {
      this.pageChange.emit(this.page() + 1);
    }
  }
}
