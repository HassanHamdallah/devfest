import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from "@angular/core";
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from "@angular/forms";
import { EventApi } from "../../../../core/event/event-api";
import {
  Organization,
  TeamCredential,
} from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { ActionState } from "../shared/action-state";
import { ActionStatus } from "../shared/action-status";
import { fieldError } from "../shared/form-rules";
import { CredentialsDialog } from "./credentials-dialog";

/** Same rule as the backend: 1–30 letters, digits and spaces, at least one letter or digit. */
const PREFIX_PATTERN = /^[A-Za-z0-9 ]*[A-Za-z0-9][A-Za-z0-9 ]*$/;

/**
 * Bulk team creation: "<prefix> 01" … with member accounts and one-time passwords, placed in the smallest
 * groups first. The server creates all teams or none.
 */
@Component({
  selector: "app-generate-teams-form",
  imports: [
    ActionStatus,
    CredentialsDialog,
    ReactiveFormsModule,
    UiButton,
    UiCard,
    UiConfirmDialog,
    UiInput,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<ui-card>
      <h3 ui-card-header>Generate teams</h3>
      <form aria-label="Generate teams" [formGroup]="form" (ngSubmit)="ask()">
        <div class="generate-teams__fields">
          <ui-input
            label="Number of teams"
            type="number"
            formControlName="count"
            [hint]="countHint()"
            [error]="countError()"
          />
          <ui-input
            label="Name prefix"
            formControlName="prefix"
            [hint]="prefixHint()"
            [error]="prefixError()"
          />
        </div>
        <ui-button type="submit" [disabled]="state.busy()"
          >Generate teams</ui-button
        >
        <app-action-status [state]="state" />
      </form>
    </ui-card>
    <ui-confirm-dialog
      [open]="confirming()"
      [title]="confirmTitle()"
      message="Each team gets a member account with a one-time password. The passwords are shown once, right after this step."
      [busy]="state.busy()"
      (confirmed)="generate()"
      (cancelled)="confirming.set(false)"
    />
    <app-credentials-dialog
      [credentials]="credentials()"
      (closed)="credentials.set([])"
    />`,
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
    .generate-teams__fields {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr));
      gap: var(--space-3);
      justify-self: stretch;
    }
  `,
})
export class GenerateTeamsForm {
  private readonly eventApi = inject(EventApi);

  readonly organization = input.required<Organization>();
  readonly changed = output<void>();

  protected readonly state = new ActionState();
  protected readonly confirming = signal(false);
  /** The one-time passwords live only here, and only while the result dialog is open. */
  protected readonly credentials = signal<readonly TeamCredential[]>([]);
  protected readonly form = new FormGroup({
    count: new FormControl("", {
      nonNullable: true,
      validators: [Validators.required, Validators.min(1), Validators.max(64)],
    }),
    prefix: new FormControl("Team", {
      nonNullable: true,
      validators: [
        Validators.required,
        Validators.maxLength(30),
        Validators.pattern(PREFIX_PATTERN),
      ],
    }),
  });

  /** Teams still missing from the planned count. */
  protected readonly openSlots = computed(() => {
    const org = this.organization();
    const active = org.groups.reduce((sum, g) => sum + g.activeTeams, 0);
    return Math.max(0, org.plannedTeamCount - active);
  });
  protected readonly countHint = computed(
    () =>
      `${this.openSlots()} of ${this.organization().plannedTeamCount} planned teams still to create.`,
  );
  protected confirmTitle(): string {
    const { count, prefix } = this.form.getRawValue();
    return `Generate ${count} teams named "${prefix.trim()} 01"…?`;
  }

  protected countError(): string | null {
    const control = this.form.controls.count;
    if (!control.touched || control.valid) return null;
    return "Enter a whole number from 1 to 64.";
  }

  protected prefixError(): string | null {
    const control = this.form.controls.prefix;
    if (control.touched && control.hasError("pattern"))
      return "Use letters, digits and spaces.";
    return fieldError(control, "Name prefix");
  }

  protected prefixHint(): string {
    const prefix = this.form.controls.prefix.value.replace(/ /g, "");
    return `Sign-in emails look like ${prefix.toLowerCase() || "team"}01@event.local.`;
  }

  protected ask(): void {
    this.form.markAllAsTouched();
    const count = Number(this.form.controls.count.value);
    if (this.form.invalid || !Number.isInteger(count)) return;
    this.state.reset();
    this.confirming.set(true);
  }

  protected async generate(): Promise<void> {
    const { count, prefix } = this.form.getRawValue();
    let created: TeamCredential[] = [];
    const ok = await this.state.run(async () => {
      created = await this.eventApi.generateTeams({
        count: Number(count),
        prefix: prefix.trim(),
      });
    }, `${count} teams created.`);
    this.confirming.set(false);
    if (!ok) return;
    this.form.controls.count.reset("");
    this.credentials.set(created);
    this.changed.emit();
  }
}
