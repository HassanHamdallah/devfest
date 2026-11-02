import { computed, signal } from "@angular/core";
import { message } from "../../../core/api";

/**
 * Holds one board endpoint's latest response for a page. Background reloads (revision changes,
 * own writes) keep the last data on screen; only the first load shows the loading state.
 * A sequence number drops responses that arrive after a newer request was started.
 */
export class BoardLoader<T> {
  readonly data = signal<T | null>(null);
  readonly loading = signal(true);
  private readonly lastError = signal<string | null>(null);
  private sequence = 0;

  /** Error to show instead of the page content (nothing loaded yet). */
  readonly initialError = computed(() =>
    this.data() === null ? this.lastError() : null,
  );
  /** Error of a background refresh while older data stays visible. */
  readonly refreshError = computed(() =>
    this.data() === null ? null : this.lastError(),
  );

  constructor(private readonly fetch: () => Promise<T>) {}

  async load(): Promise<void> {
    const ticket = ++this.sequence;
    if (this.data() === null) this.loading.set(true);
    try {
      const value = await this.fetch();
      if (ticket !== this.sequence) return;
      this.data.set(value);
      this.lastError.set(null);
    } catch (error) {
      if (ticket === this.sequence) this.lastError.set(message(error));
    } finally {
      if (ticket === this.sequence) this.loading.set(false);
    }
  }

  /** Forgets the current data, e.g. when the route switches to another room. */
  reset(): void {
    this.sequence++;
    this.data.set(null);
    this.lastError.set(null);
    this.loading.set(true);
  }

  /** Shows an error without fetching, e.g. for a malformed route parameter. */
  fail(text: string): void {
    this.sequence++;
    this.data.set(null);
    this.lastError.set(text);
    this.loading.set(false);
  }
}
