import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  input,
  signal,
} from "@angular/core";

const COPIED_MS = 2000;

/** Clipboard API where the page is a secure context; the older copy command elsewhere (plain-HTTP event network). */
async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // Fall through to the fallback below.
  }
  const area = document.createElement("textarea");
  area.value = text;
  area.setAttribute("readonly", "");
  area.style.position = "fixed";
  area.style.opacity = "0";
  document.body.append(area);
  area.select();
  try {
    return document.execCommand("copy");
  } catch {
    return false;
  } finally {
    area.remove();
  }
}

/** "Copy" capsule that confirms with "Copied" for two seconds (announced politely). */
@Component({
  selector: "ui-copy-button",
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<button type="button" class="copy" (click)="copy()">
      <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
        <path
          d="M20 9h-9a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2v-9a2 2 0 0 0-2-2zM5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"
        />
      </svg>
      {{ state() === "copied" ? "Copied" : state() === "failed" ? "Copy failed" : label() }}
    </button>
    <span class="event-sr-only" aria-live="polite">{{
      state() === "copied" ? "Copied to the clipboard." : ""
    }}</span>`,
  styles: `
    :host {
      display: inline-flex;
    }
    .copy {
      display: inline-flex;
      align-items: center;
      gap: var(--space-2);
      min-block-size: 44px;
      padding: 0 var(--space-4);
      font: inherit;
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-semibold);
      color: var(--color-text);
      background: var(--color-surface);
      border: var(--border-outline-thin);
      border-radius: var(--radius-full);
      cursor: pointer;
    }
    .copy:hover {
      background: var(--color-surface-subtle);
    }
    path {
      fill: none;
      stroke: currentcolor;
      stroke-width: 2;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
  `,
})
export class UiCopyButton {
  readonly text = input.required<string>();
  readonly label = input("Copy");

  protected readonly state = signal<"idle" | "copied" | "failed">("idle");
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      if (this.timer) clearTimeout(this.timer);
    });
  }

  protected async copy(): Promise<void> {
    this.state.set((await copyText(this.text())) ? "copied" : "failed");
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => this.state.set("idle"), COPIED_MS);
  }
}
