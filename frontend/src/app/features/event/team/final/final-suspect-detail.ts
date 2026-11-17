import { ChangeDetectionStrategy, Component, ElementRef, afterRenderEffect, input, output, viewChild } from "@angular/core";
import { FinalSuspect } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";

/**
 * The full card of one suspect in a modal <dialog> (ID, name, position, the whole statement). "Select as the
 * kidnapper" only tells the parent which suspect to put in the form's select; submitting happens there, with its own
 * confirmation. Escape, Close and a click on the backdrop all emit `closed`.
 */
@Component({
  selector: "event-final-suspect-detail",
  imports: [UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    dialog {
      inline-size: min(30rem, calc(100vw - 2 * var(--space-4)));
      max-block-size: calc(100dvh - 2 * var(--space-4));
      padding: var(--space-6);
      color: var(--color-text);
      background: var(--color-surface);
      border: var(--border-outline);
      border-radius: var(--radius-card);
      box-shadow: var(--shadow-lg);
    }
    dialog::backdrop {
      background: var(--color-overlay);
    }
    .id {
      display: inline-block;
      padding: 2px var(--space-2);
      font-family: var(--font-mono);
      font-weight: var(--font-weight-bold);
      color: var(--color-primary-text);
      background: var(--color-primary-subtle);
      border-radius: var(--radius-sm);
    }
    h2 {
      margin: var(--space-2) 0 0;
      font-size: var(--font-size-xl);
      overflow-wrap: anywhere;
    }
    .position {
      margin: 0 0 var(--space-4);
      color: var(--color-text-muted);
    }
    .label {
      margin: 0;
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-bold);
      letter-spacing: 0.06em;
      color: var(--color-text-muted);
    }
    blockquote {
      margin: var(--space-1) 0 0;
      padding: var(--space-3);
      background: var(--color-surface-subtle);
      border-radius: var(--radius-md);
      overflow-wrap: anywhere;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: var(--space-2);
      margin-block-start: var(--space-5);
    }
  `,
  template: `<dialog
    #dialogEl
    role="dialog"
    aria-modal="true"
    aria-labelledby="suspect-detail-title"
    data-suspect-detail
    (cancel)="$event.preventDefault(); closed.emit()"
    (click)="backdrop($event)"
  >
    @if (suspect(); as s) {
      <span class="id" data-detail-id>{{ s.id }}</span>
      <h2 id="suspect-detail-title" data-detail-name>{{ s.name }}</h2>
      <p class="position" data-detail-position>{{ s.position }}</p>
      <p class="label">STATEMENT</p>
      <blockquote data-detail-statement>“{{ s.statement }}”</blockquote>
      <div class="actions">
        <ui-button #closeButton variant="secondary" (click)="closed.emit()">Close</ui-button>
        <ui-button data-select-suspect (click)="chosen.emit(s)">Select as the kidnapper</ui-button>
      </div>
    }
  </dialog>`,
})
export class EventFinalSuspectDetail {
  /** The suspect to show; null keeps the dialog closed. */
  readonly suspect = input<FinalSuspect | null>(null);
  readonly chosen = output<FinalSuspect>();
  readonly closed = output<void>();

  private readonly dialogRef = viewChild.required<ElementRef<HTMLDialogElement>>("dialogEl");
  private readonly closeRef = viewChild("closeButton", { read: ElementRef<HTMLElement> });

  constructor() {
    afterRenderEffect(() => this.sync(this.suspect() !== null));
  }

  /** A click on the dialog's own box (outside its content) is a click on the backdrop. */
  protected backdrop(event: MouseEvent): void {
    if (event.target === this.dialogRef().nativeElement) this.closed.emit();
  }

  private sync(open: boolean): void {
    const dialog = this.dialogRef().nativeElement;
    if (open && !dialog.open) {
      dialog.showModal();
      this.closeRef()?.nativeElement.querySelector("button")?.focus();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  }
}
