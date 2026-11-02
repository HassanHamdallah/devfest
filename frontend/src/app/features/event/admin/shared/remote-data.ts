import { computed, signal } from "@angular/core";
import { HttpErrorResponse } from "@angular/common/http";
import { message } from "../../../../core/api";

/**
 * One page's single board endpoint. Reloads keep the last data on screen, so a revision-driven
 * refresh never flashes the loading state; only the newest request may update the signals.
 */
export class RemoteData<T> {
  private readonly value = signal<T | null>(null);
  private readonly pending = signal(false);
  private readonly failure = signal<string | null>(null);
  private readonly status = signal<number | null>(null);
  private latestRequest = 0;

  readonly data = this.value.asReadonly();
  /** Message and HTTP status of the latest failed load; cleared by the next successful one. */
  readonly error = this.failure.asReadonly();
  readonly errorStatus = this.status.asReadonly();
  /** True only for the first load, before any data arrived. */
  readonly loading = computed(
    () => this.pending() && this.value() === null && this.failure() === null,
  );
  /** Error to show instead of the page, when nothing was ever loaded. */
  readonly blockingError = computed(() =>
    this.value() === null ? this.failure() : null,
  );
  /** Error of a refresh that failed while older data is still shown. */
  readonly staleError = computed(() =>
    this.value() === null ? null : this.failure(),
  );

  constructor(private readonly fetch: () => Promise<T>) {}

  /** "Try again": drop the shown error so the loading state is visible while it retries. */
  retry(): Promise<void> {
    this.failure.set(null);
    this.status.set(null);
    return this.load();
  }

  async load(): Promise<void> {
    const request = ++this.latestRequest;
    this.pending.set(true);
    try {
      const result = await this.fetch();
      if (request !== this.latestRequest) return;
      this.value.set(result);
      this.failure.set(null);
      this.status.set(null);
    } catch (error) {
      if (request !== this.latestRequest) return;
      this.failure.set(message(error));
      this.status.set(error instanceof HttpErrorResponse ? error.status : null);
    } finally {
      if (request === this.latestRequest) this.pending.set(false);
    }
  }
}
