import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  input,
  output,
  signal,
  viewChild,
} from "@angular/core";
import { TeamCredential } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import {
  CREDENTIALS_FILE_NAME,
  credentialsText,
  downloadCredentialsCsv,
} from "./credentials-export";

/**
 * Shows freshly generated team sign-ins once (native modal <dialog>). The parent owns the list and drops
 * it on `closed`, so the passwords leave memory with the dialog; they cannot be fetched again.
 */
@Component({
  selector: "app-credentials-dialog",
  imports: [UiAlert, UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<dialog
    #dialogEl
    class="credentials-dialog"
    aria-labelledby="credentials-title"
    (cancel)="handleCancel($event)"
  >
    <h2 id="credentials-title">Team sign-ins</h2>
    <ui-alert variant="warning"
      >These passwords are shown only once. Copy or download them now; use
      "Reset password" to issue a new one later.</ui-alert
    >
    <div class="event-table-wrap">
      <table>
        <caption>
          Generated teams
        </caption>
        <thead>
          <tr>
            <th scope="col">Team</th>
            <th scope="col">Group</th>
            <th scope="col">Email</th>
            <th scope="col">Password</th>
          </tr>
        </thead>
        <tbody>
          @for (row of credentials(); track row.email) {
            <tr [attr.data-credential]="row.teamName">
              <th scope="row">{{ row.teamName }}</th>
              <td>{{ row.group }}</td>
              <td>{{ row.email }}</td>
              <td><code>{{ row.password }}</code></td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    <p class="credentials-dialog__status" role="status">{{ status() }}</p>
    <div class="credentials-dialog__actions">
      <ui-button variant="secondary" (click)="copyAll()">Copy all</ui-button>
      <ui-button variant="secondary" (click)="download()"
        >Download CSV</ui-button
      >
      <ui-button #closeButton (click)="closed.emit()">Close</ui-button>
    </div>
  </dialog>`,
  styles: `
    .credentials-dialog {
      inline-size: min(52rem, calc(100vw - 2 * var(--space-4)));
      max-block-size: calc(100vh - 2 * var(--space-4));
      padding: var(--space-6);
      color: var(--color-text);
      background: var(--color-surface);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-card);
      box-shadow: var(--shadow-lg);
    }
    .credentials-dialog::backdrop {
      background: var(--color-overlay);
    }
    h2 {
      margin: 0 0 var(--space-3);
      font-size: var(--font-size-lg);
    }
    ui-alert {
      display: block;
      margin-bottom: var(--space-3);
    }
    th[scope="row"] {
      font-size: inherit;
      letter-spacing: normal;
      color: var(--color-text);
      background: none;
    }
    code {
      font-size: var(--font-size-sm);
      overflow-wrap: anywhere;
    }
    .credentials-dialog__status {
      min-block-size: 1.25rem;
      margin: var(--space-2) 0 0;
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    .credentials-dialog__actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: var(--space-2);
      margin-top: var(--space-3);
    }
  `,
})
export class CredentialsDialog {
  /** Non-empty opens the dialog. */
  readonly credentials = input.required<readonly TeamCredential[]>();
  readonly closed = output<void>();

  protected readonly status = signal("");
  private readonly dialogRef =
    viewChild.required<ElementRef<HTMLDialogElement>>("dialogEl");
  private readonly closeRef = viewChild.required("closeButton", {
    read: ElementRef<HTMLElement>,
  });

  constructor() {
    afterRenderEffect(() => {
      const open = this.credentials().length > 0;
      const dialog = this.dialogRef().nativeElement;
      if (open && !dialog.open) {
        this.status.set("");
        dialog.showModal();
        this.closeRef().nativeElement.querySelector("button")?.focus();
      } else if (!open && dialog.open) {
        dialog.close();
      }
    });
  }

  protected handleCancel(event: Event): void {
    event.preventDefault();
    this.closed.emit();
  }

  protected async copyAll(): Promise<void> {
    try {
      await navigator.clipboard.writeText(credentialsText(this.credentials()));
      this.status.set("Copied all sign-ins to the clipboard.");
    } catch {
      this.status.set(
        "Copying is blocked in this browser. Use Download CSV instead.",
      );
    }
  }

  protected download(): void {
    downloadCredentialsCsv(this.credentials());
    this.status.set(`Downloaded ${CREDENTIALS_FILE_NAME}.`);
  }
}
