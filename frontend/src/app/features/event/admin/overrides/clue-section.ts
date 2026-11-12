import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  signal,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { EventApi } from "../../../../core/event/event-api";
import { OrganizationRoom, TeamView } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiSelect } from "../../../../shared/ui/ui-select/ui-select";
import { OverrideDialog } from "./override-dialog";
import { OverrideOutcomeView } from "./override-outcome";
import {
  NO_SELECTION,
  OverrideRun,
  SECTION_STYLES,
  findRoom,
  findTeam,
  roomOptions,
  teamOptions,
} from "./override-run";

type ClueAction = "GRANT" | "REVOKE";

const ACTIONS = [
  { value: "GRANT", label: "Grant clue" },
  { value: "REVOKE", label: "Revoke clue" },
];

/** O4: grant (MANUAL) or revoke a team's clue for one room; submissions and verdicts never change. */
@Component({
  selector: "app-clue-section",
  imports: [FormsModule, OverrideDialog, OverrideOutcomeView, UiButton, UiSelect],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [SECTION_STYLES],
  template: `<section class="override-section" id="clue" aria-labelledby="clue-heading">
    <details class="override-section__details" data-collapsible>
      <summary><h2 id="clue-heading">Manual clue</h2></summary>
    <p class="event-muted">
      Grant or revoke the clue of one room for one team. Teams see a granted
      clue as collected; only admin screens mark it "Manual".
    </p>
    <div class="override-section__fields">
      <ui-select
        label="Team"
        placeholder="Choose a team"
        [options]="teamChoices()"
        [ngModel]="team()"
        (ngModelChange)="team.set($event)"
      />
      <ui-select
        label="Room"
        placeholder="Choose a room"
        [options]="roomChoices()"
        [ngModel]="room()"
        (ngModelChange)="room.set($event)"
      />
      <ui-select
        label="Action"
        [options]="actions"
        [ngModel]="action()"
        (ngModelChange)="action.set($event)"
      />
    </div>
    @if (action() === "REVOKE") {
      <label class="override-section__check">
        <input
          type="checkbox"
          [ngModel]="alsoRecovery()"
          (ngModelChange)="alsoRecovery.set($event)"
        />
        Also remove a clue won in the recovery challenge
      </label>
    }
    <div class="event-inline">
      <ui-button [disabled]="!team() || !room()" (click)="run.ask()"
        >Review clue change…</ui-button
      >
    </div>
    <app-override-outcome [outcome]="run.outcome()" />
    <app-override-dialog
      [open]="run.open()"
      [title]="action() === 'GRANT' ? 'Grant this clue?' : 'Revoke this clue?'"
      [consequence]="consequence()"
      [variant]="action() === 'GRANT' ? 'primary' : 'danger'"
      [busy]="run.busy()"
      [error]="run.error()"
      (confirmed)="confirm($event)"
      (cancelled)="run.cancel()"
    />
  </details>
  </section>`,
})
export class ClueSection {
  private readonly eventApi = inject(EventApi);

  readonly teams = input.required<readonly TeamView[]>();
  readonly rooms = input.required<readonly OrganizationRoom[]>();
  readonly preselect = input<number | null>(null);
  readonly changed = output<void>();

  protected readonly run = new OverrideRun();
  protected readonly actions = ACTIONS;
  protected readonly teamChoices = computed(() => teamOptions(this.teams()));
  protected readonly roomChoices = computed(() => roomOptions(this.rooms()));
  protected readonly team = linkedSignal(() =>
    this.preselect() === null ? NO_SELECTION : String(this.preselect()),
  );
  protected readonly room = signal(NO_SELECTION);
  protected readonly action = signal<ClueAction>("GRANT");
  protected readonly alsoRecovery = signal(false);
  protected readonly consequence = computed(() => {
    const team = findTeam(this.teams(), this.team());
    const room = findRoom(this.rooms(), this.room());
    if (!team || !room) return "";
    if (this.action() === "GRANT") {
      return (
        `${team.name} holds the ${room.name} clue from now on (marked Manual for admins). If the team ` +
        `already holds it, nothing changes. Submissions and verdicts stay as they are.`
      );
    }
    return (
      `${team.name} loses its ${room.name} clue if it came from the room or a manual grant` +
      (this.alsoRecovery() ? ", and also if it was won in the recovery challenge." : "; a recovery clue stays.") +
      " Its final stage may change at the next refresh."
    );
  });

  protected async confirm(reason: string): Promise<void> {
    const ok = await this.run.run(() =>
      this.eventApi.manualClue(
        Number(this.team()),
        Number(this.room()),
        this.action(),
        this.action() === "REVOKE" && this.alsoRecovery(),
        reason,
      ),
    );
    if (ok) this.changed.emit();
  }
}
