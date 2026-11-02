import { inject, signal } from "@angular/core";
import { HttpErrorResponse } from "@angular/common/http";
import { Api, message } from "../../../core/api";
import { EventApi } from "../../../core/event/event-api";
import {
  PendingKind,
  PendingRequest,
  clearPendingRequest,
  createPendingRequest,
  isNetworkError,
  loadPendingRequest,
  savePendingRequest,
} from "../../../core/event/pending-request";

const UNCONFIRMED_MESSAGE =
  "The server did not confirm this attempt. It is saved on this device; press Retry to send the same attempt again.";

/**
 * One idempotent team POST (EVENT-CONTRACT §5): the attempt and its Idempotency-Key are stored
 * before sending; without a response the same key and body can be resent, also after a reload.
 * Create it in an injection context (a component field initializer).
 */
export class PendingAttempt<B, R> {
  private readonly eventApi = inject(EventApi);
  private readonly userId = inject(Api).user()?.id ?? null;

  readonly sending = signal(false);
  readonly error = signal<string | null>(null);
  /** The stored attempt that still needs a response; non-null means "Retry" is offered. */
  readonly unconfirmed = signal<PendingRequest<B> | null>(null);

  constructor(private readonly kind: PendingKind) {
    if (this.userId === null) return;
    const stored = loadPendingRequest<B>(kind, this.userId);
    this.unconfirmed.set(stored);
    if (stored) this.error.set(UNCONFIRMED_MESSAGE);
  }

  /** A new attempt with a new key. Resolves to the response, or null when it was rejected or not confirmed. */
  submit(path: string, body: B): Promise<R | null> {
    if (this.sending()) return Promise.resolve(null);
    const attempt = createPendingRequest(path, body);
    if (this.userId !== null)
      savePendingRequest(this.kind, this.userId, attempt);
    return this.send(attempt);
  }

  retry(): Promise<R | null> {
    const attempt = this.unconfirmed();
    return attempt ? this.send(attempt) : Promise.resolve(null);
  }

  // The first send and every retry share resendPending, so both always carry the stored key and body.
  private async send(attempt: PendingRequest<B>): Promise<R | null> {
    if (this.sending()) return null;
    this.sending.set(true);
    this.error.set(null);
    try {
      const result = await this.eventApi.resendPending<R>(attempt);
      this.settle();
      return result;
    } catch (e) {
      this.handleFailure(attempt, e);
      return null;
    } finally {
      this.sending.set(false);
    }
  }

  private handleFailure(attempt: PendingRequest<B>, error: unknown): void {
    if (isWorthRetrying(error)) {
      this.unconfirmed.set(attempt);
      this.error.set(
        isNetworkError(error)
          ? UNCONFIRMED_MESSAGE
          : `${message(error)} Press Retry to send the same attempt again.`,
      );
      return;
    }
    this.settle();
    this.error.set(message(error));
  }

  private settle(): void {
    this.unconfirmed.set(null);
    if (this.userId !== null) clearPendingRequest(this.kind, this.userId);
  }
}

/**
 * No response (status 0), rate limiting (429) and server errors (5xx) may not have stored the attempt;
 * resending the same key is safe because the server replays an original receipt. Any other response
 * is a final answer (a 4xx rule rejection), so the stored attempt is dropped.
 */
function isWorthRetrying(error: unknown): boolean {
  if (!(error instanceof HttpErrorResponse)) return false;
  return isNetworkError(error) || error.status === 429 || error.status >= 500;
}
