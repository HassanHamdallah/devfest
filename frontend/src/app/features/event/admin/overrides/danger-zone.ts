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
import { TeamView } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { UiSelect } from "../../../../shared/ui/ui-select/ui-select";
import { OverrideDialog } from "./override-dialog";
import { OverrideOutcomeView } from "./override-outcome";
import {
  NO_SELECTION,
  OverrideRun,
  SECTION_STYLES,
  findTeam,
  teamOptions,
} from "./override-run";

const CONFIRM_WORD = "RESET";

/**
 * O7–O9 (`data-danger-zone`): reset the final, one team, or the whole event. Each saves a snapshot first. The
 * event reset also needs the word RESET typed exactly and the admin's own password (checked by the server).
 */
@Component({
  selector: "app-danger-zone",
  imports: [FormsModule, OverrideDialog, OverrideOutcomeView, UiButton, UiInput, UiSelect],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "data-danger-zone": "" },
  styles: [
    SECTION_STYLES,
    `
      .override-section {
        border: 2px solid var(--color-error);
      }
      .danger-zone__item {
        display: grid;
        gap: var(--space-2);
        padding-top: var(--space-3);
        border-top: 1px solid var(--color-border);
      }
      h3 {
        margin: 0;
        font-size: var(--font-size-base);
      }
    `,
  ],
  template: `<section class="override-section" id="danger-zone" aria-labelledby="danger-heading">
    <h2 id="danger-heading">Danger zone</h2>
    <p class="event-muted">
      Resets delete progress. A snapshot of everything deleted is saved first
      and can be downloaded above.
    </p>
    <div class="danger-zone__item">
      <h3>Reset final</h3>
      <div class="event-inline">
        <ui-button variant="danger" (click)="finalRun.ask()">Reset final…</ui-button>
      </div>
    </div>
    <div class="danger-zone__item" id="reset-team">
      <h3>Reset one team</h3>
      <div class="override-section__fields">
        <ui-select
          label="Team to reset"
          placeholder="Choose a team"
          [options]="choices()"
          [ngModel]="team()"
          (ngModelChange)="team.set($event)"
        />
      </div>
      <div class="event-inline">
        <ui-button variant="danger" [disabled]="!team()" (click)="teamRun.ask()"
          >Reset team…</ui-button
        >
      </div>
    </div>
    <div class="danger-zone__item">
      <h3>Reset whole event</h3>
      <div class="event-inline">
        <ui-button variant="danger" (click)="openEventReset()">Reset whole event…</ui-button>
      </div>
    </div>
    <app-override-outcome [outcome]="outcome()" />

    <app-override-dialog
      [open]="finalRun.open()"
      title="Reset the final?"
      consequence="Deletes all recovery attempts, accusations, finish times, recovery clues and the automatic recovery and wrong-accusation penalties. The final closes and can be opened again; event content becomes editable. Room rounds stay as they are."
      variant="danger"
      [busy]="finalRun.busy()"
      [error]="finalRun.error()"
      (confirmed)="resetFinal($event)"
      (cancelled)="finalRun.cancel()"
    />
    <app-override-dialog
      [open]="teamRun.open()"
      title="Reset this team?"
      [consequence]="teamConsequence()"
      variant="danger"
      [busy]="teamRun.busy()"
      [error]="teamRun.error()"
      (confirmed)="resetTeam($event)"
      (cancelled)="teamRun.cancel()"
    />
    <app-override-dialog
      [open]="eventRun.open()"
      title="Reset the whole event?"
      consequence="Deletes all progress of every team (submissions, verdicts, judgements, clues, penalties, final attempts, room exceptions, arrivals), moves the event back to Not started, closes all rooms and unpublishes results. Accounts, teams, groups, rooms, managers, clue keys and content stay."
      variant="danger"
      [busy]="eventRun.busy()"
      [error]="eventRun.error()"
      [confirmDisabled]="!eventResetReady()"
      (confirmed)="resetEvent($event)"
      (cancelled)="eventRun.cancel()"
    >
      <ui-input
        label="Type RESET to confirm"
        autocomplete="off"
        [ngModel]="confirmText()"
        (ngModelChange)="confirmText.set($event)"
      />
      <ui-input
        label="Your password"
        type="password"
        autocomplete="current-password"
        [ngModel]="password()"
        (ngModelChange)="password.set($event)"
      />
    </app-override-dialog>
  </section>`,
})
export class DangerZone {
  private readonly eventApi = inject(EventApi);

  readonly teams = input.required<readonly TeamView[]>();
  readonly preselect = input<number | null>(null);
  readonly changed = output<void>();

  protected readonly finalRun = new OverrideRun();
  protected readonly teamRun = new OverrideRun();
  protected readonly eventRun = new OverrideRun();
  protected readonly choices = computed(() => teamOptions(this.teams()));
  protected readonly team = linkedSignal(() =>
    this.preselect() === null ? NO_SELECTION : String(this.preselect()),
  );
  protected readonly confirmText = signal("");
  protected readonly password = signal("");
  protected readonly eventResetReady = computed(
    () => this.confirmText() === CONFIRM_WORD && this.password().length > 0,
  );
  protected readonly outcome = computed(
    () => this.eventRun.outcome() ?? this.teamRun.outcome() ?? this.finalRun.outcome(),
  );
  protected readonly teamConsequence = computed(() => {
    const team = findTeam(this.teams(), this.team());
    return team
      ? `Deletes everything ${team.name} did: submissions and verdicts, judgements, clues, penalties, final ` +
          `attempts, finish time and room exceptions. The team, its account and its group stay. Its device is ` +
          `signed out.`
      : "";
  });

  protected openEventReset(): void {
    this.confirmText.set("");
    this.password.set("");
    this.eventRun.ask();
  }

  protected async resetFinal(reason: string): Promise<void> {
    if (await this.finalRun.run(() => this.eventApi.resetFinal(reason))) this.changed.emit();
  }

  protected async resetTeam(reason: string): Promise<void> {
    if (await this.teamRun.run(() => this.eventApi.resetTeam(Number(this.team()), reason))) {
      this.changed.emit();
    }
  }

  /** The password leaves the page only in this request and is cleared right after. */
  protected async resetEvent(reason: string): Promise<void> {
    const ok = await this.eventRun.run(() =>
      this.eventApi.resetEvent(reason, this.confirmText(), this.password()),
    );
    this.password.set("");
    if (ok) this.changed.emit();
  }
}
