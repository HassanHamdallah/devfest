import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from "@angular/core";
import { FormControl, ReactiveFormsModule } from "@angular/forms";
import { EventApi } from "../../../../core/event/event-api";
import { AccountView, Organization } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { ActionState } from "../shared/action-state";
import { ActionStatus } from "../shared/action-status";
import { fieldError, passwordValidators } from "../shared/form-rules";
import { ACCOUNT_ROLE_LABELS, accountLinkLabel } from "./teams-view";

/** A pending dialog: deactivate (confirmation) or reset password (with a field). */
type AccountDialog = { kind: "deactivate" | "password"; account: AccountView };

/** Manager and member accounts (`data-account="<email>"` rows) with activation and password reset. */
@Component({
  selector: "app-accounts-table",
  imports: [
    ActionStatus,
    ReactiveFormsModule,
    UiButton,
    UiConfirmDialog,
    UiInput,
    UiStatusChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: "./accounts-table.html",
  styles: `
    th[scope="row"] {
      font-size: inherit;
      letter-spacing: normal;
      color: var(--color-text);
      background: none;
    }
    td:last-child {
      white-space: nowrap;
    }
    app-action-status {
      margin-bottom: var(--space-3);
    }
  `,
})
export class AccountsTable {
  private readonly eventApi = inject(EventApi);

  readonly organization = input.required<Organization>();
  readonly changed = output<void>();

  protected readonly state = new ActionState();
  protected readonly dialog = signal<AccountDialog | null>(null);
  protected readonly newPassword = new FormControl("", {
    nonNullable: true,
    validators: passwordValidators,
  });

  protected readonly rows = computed(() => {
    const org = this.organization();
    return org.accounts.map((account) => ({
      account,
      roleLabel: ACCOUNT_ROLE_LABELS[account.role],
      linkLabel: accountLinkLabel(account, org),
    }));
  });
  protected readonly dialogTitle = computed(() => {
    const dialog = this.dialog();
    if (!dialog) return "";
    return dialog.kind === "deactivate"
      ? `Deactivate ${dialog.account.name}?`
      : `Reset the password of ${dialog.account.name}?`;
  });
  protected readonly dialogMessage = computed(() =>
    this.dialog()?.kind === "deactivate"
      ? "They are signed out everywhere and cannot sign in until you activate the account again."
      : "They are signed out everywhere and sign in again with the new password.",
  );
  protected readonly passwordDialogOpen = computed(
    () => this.dialog()?.kind === "password",
  );

  protected activate(account: AccountView): void {
    void this.run(
      () => this.eventApi.setAccountActive(account.id, true),
      `${account.name} can sign in again.`,
    );
  }

  protected openDialog(
    kind: AccountDialog["kind"],
    account: AccountView,
  ): void {
    this.newPassword.reset();
    this.dialog.set({ kind, account });
  }

  protected closeDialog(): void {
    if (!this.state.busy()) this.dialog.set(null);
  }

  protected passwordError(): string | null {
    return fieldError(this.newPassword, "New password");
  }

  protected async confirmDialog(): Promise<void> {
    const dialog = this.dialog();
    if (!dialog) return;
    if (dialog.kind === "deactivate") {
      await this.run(
        () => this.eventApi.setAccountActive(dialog.account.id, false),
        `${dialog.account.name} was deactivated.`,
      );
    } else {
      this.newPassword.markAsTouched();
      if (this.newPassword.invalid) return;
      await this.run(
        () =>
          this.eventApi.resetAccountPassword(
            dialog.account.id,
            this.newPassword.value,
          ),
        `Password reset for ${dialog.account.name}.`,
      );
    }
    this.dialog.set(null);
  }

  private async run(
    work: () => Promise<unknown>,
    notice: string,
  ): Promise<void> {
    if (await this.state.run(work, notice)) this.changed.emit();
  }
}
