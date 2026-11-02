import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  untracked,
} from "@angular/core";
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from "@angular/forms";
import { EventApi } from "../../../../core/event/event-api";
import {
  GroupCode,
  Organization,
  TeamView,
} from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { UiSelect } from "../../../../shared/ui/ui-select/ui-select";
import { ActionState } from "../shared/action-state";
import { ActionStatus } from "../shared/action-status";
import { fieldError, notBlank } from "../shared/form-rules";
import {
  GROUP_OPTIONS,
  NO_SELECTION,
  memberAccountOptions,
} from "./teams-view";

/** Creates a team, or edits `team` (name, group, member account, active). */
@Component({
  selector: "app-team-form",
  imports: [
    ActionStatus,
    ReactiveFormsModule,
    UiButton,
    UiCard,
    UiInput,
    UiSelect,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: "./team-form.html",
  styleUrl: "./team-form.css",
})
export class TeamForm {
  private readonly eventApi = inject(EventApi);

  readonly organization = input.required<Organization>();
  /** The team being edited; null creates a new team. */
  readonly team = input<TeamView | null>(null);
  readonly saved = output<void>();
  readonly cancelled = output<void>();

  protected readonly state = new ActionState();
  protected readonly groupOptions = GROUP_OPTIONS;
  protected readonly form = new FormGroup({
    name: new FormControl("", {
      nonNullable: true,
      validators: [notBlank, Validators.maxLength(80)],
    }),
    groupCode: new FormControl<GroupCode>("A", { nonNullable: true }),
    accountId: new FormControl(NO_SELECTION, {
      nonNullable: true,
      validators: [Validators.required],
    }),
    active: new FormControl(true, { nonNullable: true }),
  });

  protected readonly heading = computed(() => {
    const team = this.team();
    return team ? `Edit ${team.name}` : "Create team";
  });
  protected readonly submitLabel = computed(() =>
    this.team() ? "Save team" : "Create team",
  );
  protected readonly accountOptions = computed(() =>
    memberAccountOptions(this.organization(), this.team()),
  );
  /** Moving a team after round 1 would break the rotation; the server refuses it too. */
  protected readonly groupLocked = computed(
    () => this.team() !== null && this.organization().groupsLocked,
  );
  protected readonly groupHint = computed(() =>
    this.groupLocked()
      ? "Groups are locked after round 1 starts."
      : `At most ${this.organization().maxPerGroup} active teams per group.`,
  );
  protected readonly accountHint = computed(() =>
    this.accountOptions().length
      ? null
      : "Create a team representative account first.",
  );
  private syncedTeamId: number | null = null;

  constructor() {
    effect(() => {
      const team = this.team();
      untracked(() => this.sync(team));
    });
  }

  protected nameError(): string | null {
    return fieldError(this.form.controls.name, "Team name");
  }

  protected accountError(): string | null {
    return fieldError(this.form.controls.accountId, "Member account");
  }

  protected async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    const team = this.team();
    const ok = await this.state.run(
      () => this.send(team),
      team ? "Team saved." : "Team created.",
    );
    if (!ok) return;
    this.form.markAsPristine();
    if (!team) this.resetToBlank();
    this.saved.emit();
  }

  private send(team: TeamView | null): Promise<unknown> {
    const { name, groupCode, accountId, active } = this.form.getRawValue();
    const body = { name: name.trim(), groupCode, accountId: Number(accountId) };
    return team
      ? this.eventApi.updateTeam(team.id, { ...body, active })
      : this.eventApi.createTeam(body);
  }

  /** A newly chosen team always loads; a refresh of the same team only while there are no edits. */
  private sync(team: TeamView | null): void {
    const switched = (team?.id ?? null) !== this.syncedTeamId;
    if (!switched && this.form.dirty) return;
    this.syncedTeamId = team?.id ?? null;
    // Keep "Team saved." visible after the edit closes; a newly chosen team starts clean.
    if (switched && team) this.state.reset();
    if (!team) {
      if (switched) this.resetToBlank();
      return;
    }
    this.form.reset({
      name: team.name,
      groupCode: team.groupCode,
      accountId: String(team.accountId),
      active: team.active,
    });
  }

  private resetToBlank(): void {
    this.form.reset({
      name: "",
      groupCode: "A",
      accountId: NO_SELECTION,
      active: true,
    });
  }
}
