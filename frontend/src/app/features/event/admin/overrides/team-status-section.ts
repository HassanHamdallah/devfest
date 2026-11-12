import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  output,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { EventApi } from "../../../../core/event/event-api";
import { TeamView } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
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

/** Disqualify / reinstate, for one team. */
@Component({
  selector: "app-team-status-section",
  imports: [
    FormsModule,
    OverrideDialog,
    OverrideOutcomeView,
    UiButton,
    UiSelect,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [SECTION_STYLES],
  template: `<section
      class="override-section"
      id="disqualify"
      aria-labelledby="disqualify-heading"
    >
      <details class="override-section__details" data-collapsible>
      <summary><h2 id="disqualify-heading">Disqualify or reinstate</h2></summary>
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
    </details>
  </section>`,
})
export class TeamStatusSection {
  private readonly eventApi = inject(EventApi);

  readonly teams = input.required<readonly TeamView[]>();
  readonly preselect = input<number | null>(null);
  readonly changed = output<void>();

  protected readonly statusRun = new OverrideRun();
  protected readonly choices = computed(() => teamOptions(this.teams()));
  protected readonly statusTeam = linkedSignal(() =>
    this.preselect() === null ? NO_SELECTION : String(this.preselect()),
  );
  protected readonly selected = computed(() => findTeam(this.teams(), this.statusTeam()));
  protected readonly statusConsequence = computed(() => {
    const team = this.selected();
    if (!team) return "";
    return team.disqualified
      ? `${team.name} can submit again and is ranked normally.`
      : `${team.name} can no longer submit room answers or any step of the final, and is listed last ` +
          `in the ranking marked "Disqualified". Its records are kept.`;
  });

  protected async setStatus(reason: string): Promise<void> {
    const team = this.selected();
    if (!team) return;
    const ok = await this.statusRun.run(() =>
      this.eventApi.setDisqualified(team.id, !team.disqualified, reason),
    );
    if (ok) this.changed.emit();
  }
}
