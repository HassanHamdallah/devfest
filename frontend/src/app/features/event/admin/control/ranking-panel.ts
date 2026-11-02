import {
  ChangeDetectionStrategy,
  Component,
  inject,
  input,
  output,
} from "@angular/core";
import { EventApi } from "../../../../core/event/event-api";
import { RankingRow } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { RankingTable } from "../../results/ranking-table";
import { ActionState } from "../shared/action-state";
import { ActionStatus } from "../shared/action-status";
import { ControlAction } from "./control-actions";

/** Live ranking with publication and CSV export. */
@Component({
  selector: "app-ranking-panel",
  imports: [ActionStatus, RankingTable, UiButton, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section class="event-stack" aria-labelledby="ranking-heading">
    <div class="event-page-header ranking-panel__header">
      <div class="event-inline">
        <h2 id="ranking-heading">Ranking</h2>
        @if (published()) {
          <ui-status-chip variant="success">Published</ui-status-chip>
        } @else {
          <ui-status-chip variant="neutral">Not published</ui-status-chip>
        }
      </div>
      <div class="event-page-header__actions">
        @if (published()) {
          <ui-button
            variant="secondary"
            [disabled]="busy()"
            (click)="setPublication(false)"
            >Unpublish results</ui-button
          >
        } @else {
          <ui-button [disabled]="busy()" (click)="setPublication(true)"
            >Publish results</ui-button
          >
        }
        <ui-button
          variant="secondary"
          [loading]="download.busy()"
          (click)="downloadCsv()"
          >Download CSV</ui-button
        >
      </div>
    </div>
    @if (download.error()) {
      <app-action-status [state]="download" />
    }
    <app-ranking-table [rows]="rows()" />
  </section>`,
  styles: `
    h2 {
      margin: 0;
    }
    .ranking-panel__header {
      margin-bottom: 0;
    }
    .event-stack {
      gap: var(--space-3);
    }
  `,
})
export class RankingPanel {
  private readonly eventApi = inject(EventApi);

  readonly rows = input.required<readonly RankingRow[]>();
  readonly published = input.required<boolean>();
  readonly busy = input(false);
  readonly action = output<ControlAction>();

  protected readonly download = new ActionState();

  protected setPublication(published: boolean): void {
    if (!this.busy()) this.action.emit({ kind: "setPublication", published });
  }

  protected downloadCsv(): void {
    void this.download.run(
      () => this.eventApi.downloadExportCsv(),
      "CSV downloaded.",
    );
  }
}
