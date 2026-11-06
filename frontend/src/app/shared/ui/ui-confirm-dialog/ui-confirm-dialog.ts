import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  input,
  output,
  viewChild,
} from "@angular/core";
import { UiButton, UiButtonVariant } from "../ui-button/ui-button";

let nextUiConfirmDialogId = 0;

/**
 * Modal confirmation built on the native <dialog>: showModal() makes the page behind it inert and keeps
 * focus inside, and closing it returns focus to the element that opened it. The parent owns `open`;
 * Escape and "Cancel" emit `cancelled`, "Confirm" emits `confirmed`. Focus starts on "Cancel" so a
 * stray Enter never confirms a destructive action.
 */
@Component({
  selector: "ui-confirm-dialog",
  imports: [UiButton],
  templateUrl: "./ui-confirm-dialog.html",
  styleUrl: "./ui-confirm-dialog.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UiConfirmDialog {
  readonly open = input(false);
  readonly title = input.required<string>();
  readonly message = input<string | null>(null);
  readonly confirmVariant = input<UiButtonVariant>("primary");
  /** The confirm button's text, e.g. "Start anyway". */
  readonly confirmLabel = input("Confirm");
  /** Shows a spinner on "Confirm" and blocks both buttons while the action runs. */
  readonly busy = input(false);
  /** Keeps "Confirm" disabled until the parent's own check passes (e.g. a typed confirmation word). */
  readonly confirmDisabled = input(false);

  readonly confirmed = output<void>();
  readonly cancelled = output<void>();

  protected readonly titleId = `ui-confirm-dialog-${nextUiConfirmDialogId++}`;
  private readonly dialogRef =
    viewChild.required<ElementRef<HTMLDialogElement>>("dialogEl");
  private readonly cancelRef = viewChild.required("cancelButton", {
    read: ElementRef<HTMLElement>,
  });

  constructor() {
    afterRenderEffect(() => this.sync(this.open()));
  }

  protected handleCancelEvent(event: Event): void {
    // The browser would close the dialog on Escape by itself; the parent decides through `open` instead.
    event.preventDefault();
    if (!this.busy()) this.cancelled.emit();
  }

  private sync(open: boolean): void {
    const dialog = this.dialogRef().nativeElement;
    if (open && !dialog.open) {
      dialog.showModal();
      this.cancelRef().nativeElement.querySelector("button")?.focus();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  }
}
