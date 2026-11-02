import { Injectable, signal } from "@angular/core";

export interface Toast {
  id: number;
  text: string;
}

/** Toasts disappear on their own after this long (they can also be dismissed earlier). */
export const TOAST_DURATION_MS = 4000;
const MAX_TOASTS = 3;

/**
 * Short success confirmations ("Score saved", "Timer paused"), shown by the shell's toast host in addition
 * to — never instead of — the inline text next to the action. Errors are not toasted: they stay inline
 * (role="alert") until dismissed. Toasts read as labels, so a trailing full stop is dropped.
 */
@Injectable({ providedIn: "root" })
export class ToastService {
  private readonly items = signal<Toast[]>([]);
  private nextId = 0;

  readonly toasts = this.items.asReadonly();

  show(text: string): void {
    const label = text.trim().replace(/\.$/, "");
    if (!label) return;
    const toast = { id: this.nextId++, text: label };
    this.items.update((list) => [...list, toast].slice(-MAX_TOASTS));
    setTimeout(() => this.dismiss(toast.id), TOAST_DURATION_MS);
  }

  dismiss(id: number): void {
    this.items.update((list) => list.filter((toast) => toast.id !== id));
  }
}
