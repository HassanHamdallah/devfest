import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from "@angular/core";
import { message } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import { SnapshotView } from "../../../../core/event/event-models";
import { formatDateTime } from "../../../../core/event/format";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { OverrideDialog } from "./override-dialog";
import { OverrideOutcomeView } from "./override-outcome";
import { OverrideRun, SECTION_STYLES } from "./override-run";

const KIND_LABELS: Record<SnapshotView["kind"], string> = {
  MANUAL: "Manual",
  RESET_FINAL: "Before final reset",
  RESET_TEAM: "Before team reset",
  RESET_EVENT: "Before event reset",
};

/** S1: snapshots (newest first) with a JSON download each, and "Take snapshot now". Never deleted. */
@Component({
  selector: "app-snapshots-section",
  imports: [OverrideDialog, OverrideOutcomeView, UiAlert, UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [SECTION_STYLES],
  template: `<section class="override-section" id="snapshots" aria-labelledby="snapshots-heading">
    <details class="override-section__details" data-collapsible>
      <summary><h2 id="snapshots-heading">Snapshots</h2></summary>
    <p class="event-muted">
      Every reset saves a snapshot of what it deletes first. Snapshots are
      never deleted; download one to inspect it.
    </p>
    <div class="event-inline">
      <ui-button variant="secondary" (click)="run.ask()">Take snapshot now</ui-button>
    </div>
    <app-override-outcome [outcome]="run.outcome()" />
    @if (error(); as text) {
      <ui-alert variant="error">{{ text }}</ui-alert>
    }
    <div class="event-table-wrap">
      <table>
        <caption>
          Snapshots
        </caption>
        <thead>
          <tr>
            <th scope="col">Time</th>
            <th scope="col">Kind</th>
            <th scope="col">By</th>
            <th scope="col">Reason</th>
            <th scope="col">Team</th>
            <th scope="col">File</th>
          </tr>
        </thead>
        <tbody>
          @for (snapshot of snapshots(); track snapshot.id) {
            <tr [attr.data-snapshot]="snapshot.id">
              <td>{{ time(snapshot) }}</td>
              <td>{{ kindLabels[snapshot.kind] }}</td>
              <td>{{ snapshot.createdBy }}</td>
              <td>{{ snapshot.reason }}</td>
              <td>{{ snapshot.teamName ?? "—" }}</td>
              <td>
                <ui-button variant="ghost" size="sm" (click)="download(snapshot)"
                  >Download</ui-button
                >
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="6" class="event-muted">No snapshots yet.</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    <app-override-dialog
      [open]="run.open()"
      title="Take a snapshot now?"
      consequence="Saves a copy of all event progress (submissions, verdicts, judgements, clues, penalties, final attempts, room exceptions, arrivals and the event state). Nothing else changes."
      [busy]="run.busy()"
      [error]="run.error()"
      (confirmed)="take($event)"
      (cancelled)="run.cancel()"
    />
  </details>
  </section>`,
})
export class SnapshotsSection {
  private readonly eventApi = inject(EventApi);

  /** The event revision: resets elsewhere on the page add snapshots, so the list reloads when it changes. */
  readonly revision = input<number | null>(null);
  readonly changed = output<void>();

  protected readonly run = new OverrideRun();
  protected readonly kindLabels = KIND_LABELS;
  protected readonly snapshots = signal<SnapshotView[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.revision();
      untracked(() => void this.load());
    });
  }

  protected time(snapshot: SnapshotView): string {
    return formatDateTime(snapshot.createdAt);
  }

  protected async take(reason: string): Promise<void> {
    if (await this.run.run(() => this.eventApi.takeSnapshot(reason))) {
      await this.load();
      this.changed.emit();
    }
  }

  protected async download(snapshot: SnapshotView): Promise<void> {
    this.error.set(null);
    try {
      await this.eventApi.downloadSnapshot(snapshot.id);
    } catch (e) {
      this.error.set(message(e));
    }
  }

  private async load(): Promise<void> {
    try {
      this.snapshots.set(await this.eventApi.snapshots());
    } catch (e) {
      this.error.set(message(e));
    }
  }
}
