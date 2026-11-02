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

/** O5 time adjustment (±1–60 min of adjusted time) and O6 disqualify / reinstate, for one team. */
@Component({
  selector: "app-team-status-section",
  imports: [
    FormsModule,
    OverrideDialog,
    OverrideOutcomeView,
    UiButton,
    UiInput,
    UiSelect,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [SECTION_STYLES],
  template: `<section
      class="override-section"
      id="time-adjustment"
      aria-labelledby="time-heading"
    >
      <h2 id="time-heading">Time adjustment</h2>
      <p class="event-muted">
        Add or remove minutes from a team's adjusted final time, e.g. as
        compensation for downtime. It is listed as "Time adjustment" and can be
        voided like a penalty.
      </p>
      <div class="override-section__fields">
        <ui-select
          label="Team"
          placeholder="Choose a team"
          [options]="choices()"
          [ngModel]="timeTeam()"
          (ngModelChange)="timeTeam.set($event)"
        />
        <ui-input
          label="Minutes (−60 to +60)"
          type="number"
          hint="Negative values shorten the adjusted time."
          [ngModel]="minutes()"
          (ngModelChange)="minutes.set($event)"
        />
      </div>
      <div class="event-inline">
        <ui-button [disabled]="!timeTeam() || !minutesValid()" (click)="timeRun.ask()"
          >Review time adjustment…</ui-button
        >
      </div>
      <app-override-outcome [outcome]="timeRun.outcome()" />
      <app-override-dialog
        [open]="timeRun.open()"
        title="Apply this time adjustment?"
        [consequence]="timeConsequence()"
        [busy]="timeRun.busy()"
        [error]="timeRun.error()"
        (confirmed)="adjust($event)"
        (cancelled)="timeRun.cancel()"
      />
    </section>
    <section
      class="override-section"
      id="disqualify"
      aria-labelledby="disqualify-heading"
    >
      <h2 id="disqualify-heading">Disqualify or reinstate</h2>
      <p class="event-muted">
        A disqualified team stays signed in and keeps its records, but cannot
        submit anything and is listed last in the ranking without a rank.
      </p>
      <div class="override-section__fields">
        <ui-select
          label="Team"
          placeholder="Choose a team"
          [options]="choices()"
          [ngModel]="statusTeam()"
          (ngModelChange)="statusTeam.set($event)"
        />
      </div>
      <div class="event-inline">
        @if (selected()?.disqualified) {
          <ui-button [disabled]="!statusTeam()" (click)="statusRun.ask()"
            >Reinstate…</ui-button
          >
        } @else {
          <ui-button
            variant="danger"
            [disabled]="!statusTeam()"
            (click)="statusRun.ask()"
            >Disqualify…</ui-button
          >
        }
      </div>
      <app-override-outcome [outcome]="statusRun.outcome()" />
      <app-override-dialog
        [open]="statusRun.open()"
        [title]="selected()?.disqualified ? 'Reinstate this team?' : 'Disqualify this team?'"
        [consequence]="statusConsequence()"
        [variant]="selected()?.disqualified ? 'primary' : 'danger'"
        [busy]="statusRun.busy()"
        [error]="statusRun.error()"
        (confirmed)="setStatus($event)"
        (cancelled)="statusRun.cancel()"
      />
    </section>`,
})
export class TeamStatusSection {
  private readonly eventApi = inject(EventApi);

  readonly teams = input.required<readonly TeamView[]>();
  readonly preselect = input<number | null>(null);
  readonly changed = output<void>();

  protected readonly timeRun = new OverrideRun();
  protected readonly statusRun = new OverrideRun();
  protected readonly choices = computed(() => teamOptions(this.teams()));
  protected readonly timeTeam = linkedSignal(() =>
    this.preselect() === null ? NO_SELECTION : String(this.preselect()),
  );
  protected readonly statusTeam = linkedSignal(() =>
    this.preselect() === null ? NO_SELECTION : String(this.preselect()),
  );
  protected readonly minutes = signal("-5");
  protected readonly minutesValid = computed(() => {
    const value = Number(this.minutes());
    return Number.isInteger(value) && value !== 0 && Math.abs(value) <= 60;
  });
  protected readonly selected = computed(() => findTeam(this.teams(), this.statusTeam()));
  protected readonly timeConsequence = computed(() => {
    const team = findTeam(this.teams(), this.timeTeam());
    if (!team || !this.minutesValid()) return "";
    const value = Number(this.minutes());
    return (
      `${team.name}'s adjusted time changes by ${value > 0 ? "+" : ""}${value} min. It counts in the ranking ` +
      `time but not as a penalty in the "fewer penalties" tie-break.`
    );
  });
  protected readonly statusConsequence = computed(() => {
    const team = this.selected();
    if (!team) return "";
    return team.disqualified
      ? `${team.name} can submit again and is ranked normally.`
      : `${team.name} can no longer submit room answers, recovery answers or accusations, and is listed last ` +
          `in the ranking marked "Disqualified". Its records are kept.`;
  });

  protected async adjust(reason: string): Promise<void> {
    const ok = await this.timeRun.run(() =>
      this.eventApi.timeAdjustment(Number(this.timeTeam()), Number(this.minutes()), reason),
    );
    if (ok) this.changed.emit();
  }

  protected async setStatus(reason: string): Promise<void> {
    const team = this.selected();
    if (!team) return;
    const ok = await this.statusRun.run(() =>
      this.eventApi.setDisqualified(team.id, !team.disqualified, reason),
    );
    if (ok) this.changed.emit();
  }
}
