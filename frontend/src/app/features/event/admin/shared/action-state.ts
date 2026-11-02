import { computed, signal } from "@angular/core";
import { HttpErrorResponse } from "@angular/common/http";
import { message } from "../../../../core/api";

/** Saving / saved / failed state of one write action (a form or a button). */
export class ActionState {
  private readonly running = signal(false);
  private readonly failure = signal<string | null>(null);
  private readonly status = signal<number | null>(null);
  private readonly success = signal<string | null>(null);

  readonly busy = this.running.asReadonly();
  readonly error = this.failure.asReadonly();
  readonly notice = this.success.asReadonly();
  /** 409: a stale version, a rule rejection or a lock; reloading shows the current state. */
  readonly conflict = computed(() => this.status() === 409);

  /** Runs `work` unless another run is in flight; resolves true when it succeeded. */
  async run(
    work: () => Promise<unknown>,
    savedNotice: string,
  ): Promise<boolean> {
    if (this.running()) return false;
    this.reset();
    this.running.set(true);
    try {
      await work();
      this.success.set(savedNotice);
      return true;
    } catch (error) {
      this.fail(error);
      return false;
    } finally {
      this.running.set(false);
    }
  }

  /** Shows a client-side validation problem the same way as a server rejection. */
  reject(text: string): void {
    this.reset();
    this.failure.set(text);
  }

  reset(): void {
    this.failure.set(null);
    this.status.set(null);
    this.success.set(null);
  }

  private fail(error: unknown): void {
    this.failure.set(message(error));
    this.status.set(error instanceof HttpErrorResponse ? error.status : null);
  }
}
