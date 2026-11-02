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
import { message } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import {
  OrganizationRoom,
  RotationPreviewView,
  TeamView,
} from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiSelect } from "../../../../shared/ui/ui-select/ui-select";
import { OverrideDialog } from "./override-dialog";
import { OverrideOutcomeView } from "./override-outcome";
import {
  NO_SELECTION,
  OverrideRun,
  SECTION_STYLES,
  teamOptions,
} from "./override-run";
import { RotationPreviewTable } from "./rotation-preview-table";

/** O1: exchange the groups of two active teams, after the server's preview of their rooms. */
@Component({
  selector: "app-swap-section",
  imports: [
    FormsModule,
    OverrideDialog,
    OverrideOutcomeView,
    RotationPreviewTable,
    UiAlert,
    UiButton,
    UiSelect,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [SECTION_STYLES],
  template: `<section class="override-section" id="swap" aria-labelledby="swap-heading">
    <h2 id="swap-heading">Swap teams</h2>
    <p class="event-muted">
      Exchange the groups of two active teams. Group sizes stay the same.
    </p>
    <div class="override-section__fields">
      <ui-select
        label="First team"
        placeholder="Choose a team"
        [options]="options()"
        [ngModel]="teamA()"
        (ngModelChange)="teamA.set($event); preview.set(null)"
      />
      <ui-select
        label="Second team"
        placeholder="Choose a team"
        [options]="options()"
        [ngModel]="teamB()"
        (ngModelChange)="teamB.set($event); preview.set(null)"
      />
    </div>
    <div class="event-inline">
      <ui-button
        variant="secondary"
        [disabled]="!chosen()"
        [loading]="loading()"
        (click)="loadPreview()"
        >Preview swap</ui-button
      >
      <ui-button [disabled]="!preview()" (click)="run.ask()"
        >Swap teams…</ui-button
      >
    </div>
    @if (previewError(); as text) {
      <ui-alert variant="error">{{ text }}</ui-alert>
    }
    @if (preview(); as rows) {
      <app-rotation-preview-table
        [preview]="rows"
        [rooms]="rooms()"
        caption="Swap preview"
      />
    }
    <app-override-outcome [outcome]="run.outcome()" />
    <app-override-dialog
      [open]="run.open()"
      title="Swap these teams?"
      [consequence]="consequence()"
      [busy]="run.busy()"
      [error]="run.error()"
      (confirmed)="confirm($event)"
      (cancelled)="run.cancel()"
    />
  </section>`,
})
export class SwapSection {
  private readonly eventApi = inject(EventApi);

  readonly teams = input.required<readonly TeamView[]>();
  readonly rooms = input.required<readonly OrganizationRoom[]>();
  readonly preselect = input<number | null>(null);
  readonly changed = output<void>();

  protected readonly run = new OverrideRun();
  protected readonly options = computed(() =>
    teamOptions(this.teams().filter((team) => team.active)),
  );
  protected readonly teamA = linkedSignal(() =>
    this.preselect() === null ? NO_SELECTION : String(this.preselect()),
  );
  protected readonly teamB = signal(NO_SELECTION);
  protected readonly preview = signal<RotationPreviewView[] | null>(null);
  protected readonly previewError = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly chosen = computed(
    () => !!this.teamA() && !!this.teamB() && this.teamA() !== this.teamB(),
  );
  protected readonly consequence = computed(() => {
    const [a, b] = this.preview() ?? [];
    if (!a || !b) return "";
    const warnings = a.warnings.length + b.warnings.length;
    return (
      `${a.teamName} moves from group ${a.fromGroup} to group ${a.toGroup} and ${b.teamName} from group ` +
      `${b.fromGroup} to group ${b.toGroup}. Their remaining rooms follow the new groups as in the preview` +
      (warnings ? ` (${warnings} warning${warnings === 1 ? "" : "s"}).` : ".")
    );
  });

  protected async loadPreview(): Promise<void> {
    if (!this.chosen()) return;
    this.loading.set(true);
    this.previewError.set(null);
    try {
      this.preview.set(
        await this.eventApi.swapPreview(Number(this.teamA()), Number(this.teamB())),
      );
    } catch (e) {
      this.previewError.set(message(e));
    } finally {
      this.loading.set(false);
    }
  }

  protected async confirm(reason: string): Promise<void> {
    const ok = await this.run.run(() =>
      this.eventApi.swapTeams(Number(this.teamA()), Number(this.teamB()), reason),
    );
    if (!ok) return;
    this.preview.set(null);
    this.changed.emit();
  }
}
