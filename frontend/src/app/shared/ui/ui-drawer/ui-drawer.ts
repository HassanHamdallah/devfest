import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  input,
  output,
  viewChild,
} from "@angular/core";

let nextUiDrawerId = 0;

export type UiDrawerSide = "start" | "end";

/**
 * Side panel on the native modal <dialog>, like ui-confirm-dialog: the page behind is inert, focus stays
 * inside and returns to the opener on close. The parent owns `open`; Escape, the close button and a click
 * on the backdrop emit `closed`.
 */
@Component({
  selector: "ui-drawer",
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<dialog
    #dialogEl
    class="ui-drawer"
    [class.ui-drawer--start]="side() === 'start'"
    [attr.aria-labelledby]="titleId"
    (cancel)="handleCancel($event)"
    (click)="handleBackdrop($event)"
  >
    <div class="ui-drawer__panel">
      <header class="ui-drawer__header">
        <h2 class="ui-drawer__title" [id]="titleId">{{ heading() }}</h2>
        <button
          type="button"
          class="ui-drawer__close"
          [attr.aria-label]="closeLabel()"
          (click)="closed.emit()"
        >
          <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
            <path d="M6 6l12 12M18 6 6 18" />
          </svg>
        </button>
      </header>
      <div class="ui-drawer__body"><ng-content /></div>
    </div>
  </dialog>`,
  styles: `
    .ui-drawer {
      inline-size: min(28rem, 100vw);
      max-inline-size: 100vw;
      block-size: 100dvh;
      max-block-size: 100dvh;
      margin: 0 0 0 auto;
      padding: 0;
      color: var(--color-text);
      background: var(--color-surface);
      border: 0;
      border-inline-start: var(--border-outline);
      box-shadow: var(--shadow-lg);
    }
    .ui-drawer--start {
      margin: 0 auto 0 0;
      border-inline-start: 0;
      border-inline-end: var(--border-outline);
    }
    .ui-drawer::backdrop {
      background: var(--color-overlay);
    }
    .ui-drawer__panel {
      display: grid;
      grid-template-rows: auto minmax(0, 1fr);
      block-size: 100%;
    }
    .ui-drawer__header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3);
      padding: var(--space-4) var(--space-4) var(--space-3) var(--space-5);
      border-block-end: 1px solid var(--color-border);
    }
    .ui-drawer__title {
      margin: 0;
      font-size: var(--font-size-lg);
      letter-spacing: var(--letter-spacing-heading);
      overflow-wrap: anywhere;
    }
    .ui-drawer__close {
      display: grid;
      place-items: center;
      flex-shrink: 0;
      inline-size: 44px;
      block-size: 44px;
      padding: 0;
      color: var(--color-text);
      background: transparent;
      border: var(--border-outline-thin);
      border-radius: 50%;
      cursor: pointer;
    }
    .ui-drawer__close:hover {
      background: var(--color-surface-subtle);
    }
    .ui-drawer__close path {
      fill: none;
      stroke: currentcolor;
      stroke-width: 2;
      stroke-linecap: round;
    }
    .ui-drawer__body {
      overflow-y: auto;
      padding: var(--space-5);
    }
    @media (prefers-reduced-motion: no-preference) {
      .ui-drawer[open] {
        animation: ui-drawer-in var(--duration-base) var(--ease-out);
      }
      .ui-drawer--start[open] {
        animation-name: ui-drawer-in-start;
      }
    }
    @keyframes ui-drawer-in {
      from {
        transform: translateX(24px);
        opacity: 0;
      }
    }
    @keyframes ui-drawer-in-start {
      from {
        transform: translateX(-24px);
        opacity: 0;
      }
    }
  `,
})
export class UiDrawer {
  readonly open = input(false);
  readonly heading = input.required<string>();
  readonly side = input<UiDrawerSide>("end");
  readonly closeLabel = input("Close panel");
  readonly closed = output<void>();

  protected readonly titleId = `ui-drawer-${nextUiDrawerId++}`;
  private readonly dialogRef =
    viewChild.required<ElementRef<HTMLDialogElement>>("dialogEl");

  constructor() {
    afterRenderEffect(() => {
      const dialog = this.dialogRef().nativeElement;
      if (this.open() && !dialog.open) dialog.showModal();
      else if (!this.open() && dialog.open) dialog.close();
    });
  }

  protected handleCancel(event: Event): void {
    // The parent decides through `open`; the browser would otherwise close the dialog by itself.
    event.preventDefault();
    this.closed.emit();
  }

  /** The panel fills the dialog, so a click that lands on the dialog element itself hit the backdrop. */
  protected handleBackdrop(event: MouseEvent): void {
    if (event.target === this.dialogRef().nativeElement) this.closed.emit();
  }
}
