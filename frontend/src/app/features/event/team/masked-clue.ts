import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  input,
  signal,
} from "@angular/core";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { NavigationStart, Router } from "@angular/router";
import { filter } from "rxjs";
import { UiCopyButton } from "../../../shared/ui/ui-copy-button/ui-copy-button";

/** A revealed clue hides itself again after this long. */
export const REVEAL_MS = 15_000;
const MASK = "••••••••";

/**
 * An official clue behind an eye toggle, so nobody nearby reads it by accident: dots by default, shown on request
 * and hidden again after 15 s, when the tab is hidden and when the user navigates. Copy works without revealing.
 */
@Component({
  selector: "event-masked-clue",
  imports: [UiCopyButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "[attr.data-revealed]": "revealed()" },
  template: `<span class="value" data-clue-value [class.value--masked]="!revealed()">
      @if (revealed()) {
        {{ value() }}
      } @else {
        <span aria-hidden="true">{{ mask }}</span><span class="event-sr-only">Clue hidden</span>
      }
    </span>
    <button
      type="button"
      class="eye"
      [attr.aria-label]="revealed() ? 'Hide clue' : 'Show clue'"
      [attr.aria-pressed]="revealed()"
      (click)="toggle()"
    >
      <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
        <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" />
        <circle cx="12" cy="12" r="3" />
        @if (revealed()) {
          <path d="M3 3l18 18" />
        }
      </svg>
    </button>
    <ui-copy-button [text]="value()" />`,
  styles: `
    :host {
      display: inline-flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
    }
    .value {
      min-inline-size: 8ch;
      padding: var(--space-1) var(--space-3);
      font-family: var(--font-mono);
      font-weight: var(--font-weight-semibold);
      overflow-wrap: anywhere;
      background: var(--color-surface-subtle);
      border-radius: var(--radius-sm);
    }
    .value--masked {
      letter-spacing: 0.15em;
      color: var(--color-text-muted);
    }
    .eye {
      display: grid;
      place-items: center;
      inline-size: 44px;
      block-size: 44px;
      padding: 0;
      color: var(--color-text);
      background: var(--color-surface);
      border: var(--border-outline-thin);
      border-radius: var(--radius-full);
      cursor: pointer;
    }
    .eye:hover {
      background: var(--color-surface-subtle);
    }
    path,
    circle {
      fill: none;
      stroke: currentcolor;
      stroke-width: 2;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
  `,
})
export class EventMaskedClue {
  readonly value = input.required<string>();

  protected readonly mask = MASK;
  protected readonly revealed = signal(false);
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    const hideWhenHidden = () => {
      if (document.visibilityState === "hidden") this.hide();
    };
    document.addEventListener("visibilitychange", hideWhenHidden);
    inject(Router)
      .events.pipe(
        filter((event) => event instanceof NavigationStart),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.hide());
    inject(DestroyRef).onDestroy(() => {
      document.removeEventListener("visibilitychange", hideWhenHidden);
      this.clearTimer();
    });
  }

  protected toggle(): void {
    if (this.revealed()) {
      this.hide();
      return;
    }
    this.revealed.set(true);
    this.clearTimer();
    this.timer = setTimeout(() => this.hide(), REVEAL_MS);
  }

  private hide(): void {
    this.clearTimer();
    this.revealed.set(false);
  }

  private clearTimer(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }
}
