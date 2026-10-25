import { ChangeDetectionStrategy, Component, inject } from "@angular/core";
import { ToastService } from "./toast.service";

/**
 * Bottom-right stack of toasts in one polite live region. The toast body lets clicks pass through so it
 * never blocks the page underneath; only its dismiss button takes the pointer. Nothing here moves focus.
 */
@Component({
  selector: "app-toast-host",
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="toast-host" role="status" aria-live="polite">
    @for (toast of toasts(); track toast.id) {
      <div class="toast">
        <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
          <circle cx="12" cy="12" r="10" class="toast__badge" />
          <path d="m7.5 12.5 3 3 6-6.5" class="toast__tick" />
        </svg>
        <p class="toast__text">{{ toast.text }}</p>
        <button
          type="button"
          class="toast__dismiss"
          aria-label="Dismiss notification"
          (click)="service.dismiss(toast.id)"
        >
          <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
            <path d="M6 6l12 12M18 6 6 18" />
          </svg>
        </button>
      </div>
    }
  </div>`,
  styles: `
    .toast-host {
      position: fixed;
      inset-inline-end: var(--space-4);
      inset-block-end: var(--space-4);
      z-index: var(--z-toast);
      display: grid;
      gap: var(--space-2);
      inline-size: min(22rem, calc(100vw - 2 * var(--space-4)));
      pointer-events: none;
    }
    .toast {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      padding: var(--space-2) var(--space-2) var(--space-2) var(--space-4);
      color: var(--color-surface);
      background: var(--color-text);
      border-radius: var(--radius-full);
      box-shadow: var(--shadow-lg);
    }
    .toast__badge {
      fill: var(--brand-green);
    }
    .toast__tick {
      fill: none;
      stroke: var(--color-text);
      stroke-width: 2.4;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    .toast__text {
      flex: 1;
      margin: 0;
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-semibold);
      overflow-wrap: anywhere;
    }
    .toast__dismiss {
      display: grid;
      place-items: center;
      flex-shrink: 0;
      inline-size: 32px;
      block-size: 32px;
      padding: 0;
      color: inherit;
      background: transparent;
      border: 0;
      border-radius: 50%;
      cursor: pointer;
      pointer-events: auto;
    }
    .toast__dismiss:hover {
      background: color-mix(in srgb, var(--color-surface) 18%, transparent);
    }
    .toast__dismiss path {
      fill: none;
      stroke: currentcolor;
      stroke-width: 2;
      stroke-linecap: round;
    }
    @media (prefers-reduced-motion: no-preference) {
      .toast {
        animation: toast-in var(--duration-base) var(--ease-out);
      }
    }
    @keyframes toast-in {
      from {
        opacity: 0;
        transform: translateY(8px);
      }
    }
  `,
})
export class ToastHost {
  protected readonly service = inject(ToastService);
  protected readonly toasts = this.service.toasts;
}
