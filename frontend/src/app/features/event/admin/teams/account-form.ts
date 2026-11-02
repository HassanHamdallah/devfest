import {
  ChangeDetectionStrategy,
  Component,
  inject,
  output,
} from "@angular/core";
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from "@angular/forms";
import { EventApi } from "../../../../core/event/event-api";
import { EventAccountRole } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { UiSelect } from "../../../../shared/ui/ui-select/ui-select";
import { ActionState } from "../shared/action-state";
import { ActionStatus } from "../shared/action-status";
import { fieldError, notBlank, passwordValidators } from "../shared/form-rules";
import { ROLE_OPTIONS } from "./teams-view";

const FIELD_LABELS = { email: "Email", name: "Name", password: "Password" };

/** Creates a room manager or team representative account. */
@Component({
  selector: "app-account-form",
  imports: [
    ActionStatus,
    ReactiveFormsModule,
    UiButton,
    UiCard,
    UiInput,
    UiSelect,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<ui-card>
    <h3 ui-card-header>Create account</h3>
    <form aria-label="Create account" [formGroup]="form" (ngSubmit)="create()">
      <div class="account-form__fields">
        <ui-input
          label="Email"
          type="email"
          autocomplete="off"
          formControlName="email"
          [error]="error('email')"
        />
        <ui-input label="Name" formControlName="name" [error]="error('name')" />
        <ui-input
          label="Password"
          type="password"
          autocomplete="new-password"
          hint="12 to 72 characters."
          formControlName="password"
          [error]="error('password')"
        />
        <ui-select
          label="Role"
          [options]="roleOptions"
          formControlName="role"
        />
      </div>
      <ui-button type="submit" [loading]="state.busy()"
        >Create account</ui-button
      >
      <app-action-status [state]="state" />
    </form>
  </ui-card>`,
  styles: `
    h3 {
      margin: 0;
      font-size: var(--font-size-lg);
    }
    form {
      display: grid;
      justify-items: start;
      gap: var(--space-3);
    }
    .account-form__fields {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr));
      gap: var(--space-3);
      justify-self: stretch;
    }
  `,
})
export class AccountForm {
  private readonly eventApi = inject(EventApi);
  readonly changed = output<void>();

  protected readonly roleOptions = ROLE_OPTIONS;
  protected readonly state = new ActionState();
  protected readonly form = new FormGroup({
    email: new FormControl("", {
      nonNullable: true,
      validators: [
        Validators.required,
        Validators.email,
        Validators.maxLength(254),
      ],
    }),
    name: new FormControl("", {
      nonNullable: true,
      validators: [notBlank, Validators.maxLength(80)],
    }),
    password: new FormControl("", {
      nonNullable: true,
      validators: passwordValidators,
    }),
    role: new FormControl<EventAccountRole>("MEMBER", { nonNullable: true }),
  });

  protected error(field: "email" | "name" | "password"): string | null {
    return fieldError(this.form.controls[field], FIELD_LABELS[field]);
  }

  protected async create(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    const value = this.form.getRawValue();
    const created = await this.state.run(
      () =>
        this.eventApi.createAccount({
          ...value,
          email: value.email.trim(),
          name: value.name.trim(),
        }),
      `Account created for ${value.email.trim()}.`,
    );
    if (!created) return;
    this.form.reset();
    this.changed.emit();
  }
}
