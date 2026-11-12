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
  GROUP_CODES,
  GroupCode,
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

const GROUP_OPTIONS = GROUP_CODES.map((code) => ({
  value: code,
  label: `Group ${code}`,
}));

/** O2: move one team to another group within the group limit, after the server's preview of its rooms. */
@Component({
  selector: "app-move-section",
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
  template: `<section class="override-section" id="move" aria-labelledby="move-heading">
    <details class="override-section__details" data-collapsible>
      <summary><h2 id="move-heading">Move team</h2></summary>
    <p class="event-muted">
      Move a team to another group. The group limit still applies; if the
      group is full, swap instead.
    </p>
    <div class="override-section__fields">
      <ui-select
        label="Team"
        placeholder="Choose a team"
        [options]="options()"
        [ngModel]="team()"
        (ngModelChange)="team.set($event); preview.set(null)"
      />
      <ui-select
        label="Target group"
        placeholder="Choose a group"
        [options]="groups"
        [ngModel]="group()"
        (ngModelChange)="group.set($event); preview.set(null)"
      />
    </div>
    <div class="event-inline">
      <ui-button
        variant="secondary"
        [disabled]="!team() || !group()"
        [loading]="loading()"
        (click)="loadPreview()"
        >Preview move</ui-button
      >
      <ui-button [disabled]="!preview()" (click)="run.ask()"
        >Move team…</ui-button
      >
    </div>
    @if (previewError(); as text) {
      <ui-alert variant="error">{{ text }}</ui-alert>
    }
    @if (preview(); as rows) {
      <app-rotation-preview-table
        [preview]="rows"
        [rooms]="rooms()"
        caption="Move preview"
      />
    }
    <app-override-outcome [outcome]="run.outcome()" />
    <app-override-dialog
      [open]="run.open()"
      title="Move this team?"
      [consequence]="consequence()"
      [busy]="run.busy()"
      [error]="run.error()"
      (confirmed)="confirm($event)"
      (cancelled)="run.cancel()"
    />
  </details>
  </section>`,
})
export class MoveSection {
  private readonly eventApi = inject(EventApi);

  readonly teams = input.required<readonly TeamView[]>();
  readonly rooms = input.required<readonly OrganizationRoom[]>();
  readonly preselect = input<number | null>(null);
  readonly changed = output<void>();

  protected readonly run = new OverrideRun();
  protected readonly groups = GROUP_OPTIONS;
  protected readonly options = computed(() => teamOptions(this.teams()));
  protected readonly team = linkedSignal(() =>
    this.preselect() === null ? NO_SELECTION : String(this.preselect()),
  );
  protected readonly group = signal<string>(NO_SELECTION);
  protected readonly preview = signal<RotationPreviewView[] | null>(null);
  protected readonly previewError = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly consequence = computed(() => {
    const [row] = this.preview() ?? [];
    if (!row) return "";
    return (
      `${row.teamName} moves from group ${row.fromGroup} to group ${row.toGroup}. Its remaining rooms ` +
      `follow group ${row.toGroup} as in the preview` +
      (row.warnings.length ? `: ${row.warnings.join(" ")}` : ".")
    );
  });

  protected async loadPreview(): Promise<void> {
    this.loading.set(true);
    this.previewError.set(null);
    try {
      this.preview.set(
        await this.eventApi.movePreview(Number(this.team()), this.group() as GroupCode),
      );
    } catch (e) {
      this.previewError.set(message(e));
    } finally {
      this.loading.set(false);
    }
  }

  /** The admin saw the preview, so the impact is confirmed explicitly (required after round 1). */
  protected async confirm(reason: string): Promise<void> {
    const ok = await this.run.run(() =>
      this.eventApi.moveTeam(Number(this.team()), this.group() as GroupCode, reason, true),
    );
    if (!ok) return;
    this.preview.set(null);
    this.changed.emit();
  }
}
