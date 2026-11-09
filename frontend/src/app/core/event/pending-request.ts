import { HttpErrorResponse } from "@angular/common/http";

/**
 * Submission reliability (EVENT-CONTRACT §5): an attempt is persisted BEFORE it is sent, so a network
 * failure or a reload can resend the same Idempotency-Key and body and the server replays the original receipt.
 */
export type PendingKind = "submission" | "recovery" | "unlock" | "layer";

export interface PendingRequest<B> {
  key: string;
  /** API path relative to the base, e.g. roomSubmissionPath(roomId). */
  path: string;
  body: B;
}

const storageKey = (kind: PendingKind, userId: number) =>
  `devfest.event.pending.${kind}.${userId}`;

export function createPendingRequest<B>(
  path: string,
  body: B,
): PendingRequest<B> {
  return { key: newIdempotencyKey(), path, body };
}

export function savePendingRequest<B>(
  kind: PendingKind,
  userId: number,
  request: PendingRequest<B>,
): void {
  try {
    localStorage.setItem(storageKey(kind, userId), JSON.stringify(request));
  } catch {
    // Storage can be full or blocked; the attempt is still sent, it just cannot survive a reload.
  }
}

export function loadPendingRequest<B>(
  kind: PendingKind,
  userId: number,
): PendingRequest<B> | null {
  try {
    const raw = localStorage.getItem(storageKey(kind, userId));
    return raw ? parsePending<B>(raw) : null;
  } catch {
    return null;
  }
}

export function clearPendingRequest(kind: PendingKind, userId: number): void {
  try {
    localStorage.removeItem(storageKey(kind, userId));
  } catch {
    // Nothing to clean up when storage is unavailable.
  }
}

/** True when no response arrived (status 0): keep the pending attempt and offer "Retry". */
export function isNetworkError(error: unknown): boolean {
  return error instanceof HttpErrorResponse && error.status === 0;
}

/**
 * crypto.randomUUID only exists in secure contexts; the event may be served over plain HTTP on a LAN,
 * so fall back to a version 4 UUID built from crypto.getRandomValues, which is available everywhere.
 */
export function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0"));
  return [
    hex.slice(0, 4),
    hex.slice(4, 6),
    hex.slice(6, 8),
    hex.slice(8, 10),
    hex.slice(10, 16),
  ]
    .map((part) => part.join(""))
    .join("-");
}

function parsePending<B>(raw: string): PendingRequest<B> | null {
  const value: unknown = JSON.parse(raw);
  if (typeof value !== "object" || value === null) return null;
  const candidate = value as Partial<PendingRequest<B>>;
  const valid =
    typeof candidate.key === "string" &&
    typeof candidate.path === "string" &&
    candidate.body !== undefined;
  return valid ? (candidate as PendingRequest<B>) : null;
}
