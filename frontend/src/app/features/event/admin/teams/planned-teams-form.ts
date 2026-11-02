import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  output,
  untracked,
} from "@angular/core";
import { toSignal } from "@angular/core/rxjs-interop";
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from "@angular/forms";
import { EventApi } from "../../../../core/event/event-api";
import { Organization } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { ActionState } from "../shared/action-state";
import { ActionStatus } from "../shared/action-status";

export const MIN_PLANNED_TEAMS = 1;
export const MAX_PLANNED_TEAMS = 64;

/** ceil(planned / 4), the same rule the server enforces; null for an invalid count. */
export function maxPerGroup(planned: number): number | null {
  return Number.isInteger(planned) &&
    planned >= MIN_PLANNED_TEAMS &&
    planned <= MAX_PLANNED_TEAMS
    ? Math.ceil(planned / 4)
    : null;
}

/** The planned team count N (1..64); a group may hold at most ceil(N / 4) active teams. */
@Component({
  selector: "app-planned-teams-form",
  imports: [ActionStatus, ReactiveFormsModule, UiButton, UiInput],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<form
    class="planned-teams"
    aria-label="Planned teams"
    [formGroup]="form"
    (ngSubmit)="save()"
  >
    <ui-input
      label="Planned teams"
      type="number"
      [hint]="hint()"
      [error]="error()"
      formControlName="planned"
    />
    <ui-button type="submit" variant="secondary" [loading]="state.busy()"
      >Save</ui-button
    >
    <app-action-status [state]="state" />
  </form>`,
  styles: `
    .planned-teams {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: var(--space-3);
    }
    ui-input {
      width: 12rem;
    }
    ui-button {
      margin-top: 1.75rem;
    }
    app-action-status {
      flex-basis: 100%;
    }
  `,
})
export class PlannedTeamsForm {
  private readonly eventApi = inject(EventApi);

  readonly organization = input.required<Organization>();
  readonly changed = output<void>();

  protected readonly state = new ActionState();
  protected readonly planned = new FormControl("", {
    nonNullable: true,
    validators: [
      Validators.required,
      Validators.min(MIN_PLANNED_TEAMS),
      Validators.max(MAX_PLANNED_TEAMS),
    ],
  });
  protected readonly form = new FormGroup({ planned: this.planned });
  private readonly value = toSignal(this.planned.valueChanges, {
    initialValue: "",
  });

  constructor() {
    // Follow the server value unless the organizer is typing a new one.
    effect(() => {
      const count = this.organization().plannedTeamCount;
      untracked(() => {
        if (!this.planned.dirty) this.planned.reset(String(count));
      });
    });
  }

  protected hint(): string {
    const max = maxPerGroup(Number(this.value()));
    return max === null ? "1 to 64 teams." : `Max per group: ${max}`;
  }

  protected error(): string | null {
    if (!this.planned.touched || maxPerGroup(Number(this.value())) !== null)
      return null;
    return "Enter a whole number from 1 to 64.";
  }

  protected async save(): Promise<void> {
    this.planned.markAsTouched();
    const count = Number(this.planned.value);
    if (maxPerGroup(count) === null) return;
    const ok = await this.state.run(
      () => this.eventApi.setPlannedTeamCount(count),
      `Planned teams set to ${count}.`,
    );
    if (!ok) return;
    this.planned.markAsPristine();
    this.changed.emit();
  }
}
