import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from "@angular/core";
import { EventApi } from "../../../core/event/event-api";
import { reloadOnRevisionChange } from "../../../core/event/revision-sync";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { DeductionLog } from "./scores/deduction-log";
import { FinalTable } from "./scores/final-table";
import { ScoreboardTable } from "./scores/scoreboard-table";
import { ActionState } from "./shared/action-state";
import { ActionStatus } from "./shared/action-status";
import { RemoteData } from "./shared/remote-data";

type Tab = "SCOREBOARD" | "FINAL" | "LOG";

/** Live data changes without an event revision (wrong attempts, the final's clock): ask again while open. */
const REFRESH_MS = 5000;

/**
 * /event/admin/scores: the overall scoreboard (total out of 100 with the room lines and the final's parts), the live
 * final table and the full hint and cheating log. Organizers only.
 */
@Component({
  imports: [ActionStatus, DeductionLog, FinalTable, ScoreboardTable, UiAlert, UiButton, UiPageState],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .tabs {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      margin-block-end: var(--space-4);
    }
  `,
  template: `<header class="event-page-header">
      <h1>Scores</h1>
      <div class="event-page-header__actions">
        <ui-button variant="secondary" [loading]="download.busy()" (click)="downloadCsv()">Download CSV</ui-button>
      </div>
    </header>
    @if (download.error()) {
      <app-action-status [state]="download" />
    }
    <div class="tabs" role="tablist" aria-label="Score views">
      <ui-button
        role="tab"
        variant="secondary"
        [pressed]="tab() === 'SCOREBOARD'"
        [attr.aria-selected]="tab() === 'SCOREBOARD'"
        (click)="tab.set('SCOREBOARD')"
        >Scoreboard</ui-button
      >
      <ui-button
        role="tab"
        variant="secondary"
        [pressed]="tab() === 'FINAL'"
        [attr.aria-selected]="tab() === 'FINAL'"
        (click)="tab.set('FINAL')"
        >Final</ui-button
      >
      <ui-button
        role="tab"
        variant="secondary"
        [pressed]="tab() === 'LOG'"
        [attr.aria-selected]="tab() === 'LOG'"
        (click)="tab.set('LOG')"
        >Penalty log</ui-button
      >
    </div>
    @switch (tab()) {
      @case ("SCOREBOARD") {
        <ui-page-state
          [loading]="scoreboard.loading()"
          loadingLabel="Loading the scoreboard…"
          [error]="scoreboard.blockingError()"
          (retry)="scoreboard.retry()"
        />
        @if (scoreboard.staleError(); as error) {
          <ui-alert variant="warning">Showing the last loaded scoreboard. {{ error }}</ui-alert>
        }
        @if (scoreboard.data(); as rows) {
          <app-scoreboard-table [rows]="rows" />
        }
      }
      @case ("FINAL") {
        <ui-page-state
          [loading]="finalBoard.loading()"
          loadingLabel="Loading the final…"
          [error]="finalBoard.blockingError()"
          (retry)="finalBoard.retry()"
        />
        @if (finalBoard.staleError(); as error) {
          <ui-alert variant="warning">Showing the last loaded table. {{ error }}</ui-alert>
        }
        @if (finalBoard.data(); as rows) {
          <app-final-table [rows]="rows" />
        }
      }
      @case ("LOG") {
        <ui-page-state
          [loading]="log.loading()"
          loadingLabel="Loading the penalty log…"
          [error]="log.blockingError()"
          (retry)="log.retry()"
        />
        @if (log.data(); as entries) {
          <app-deduction-log [log]="entries" (changed)="reload()" />
        }
      }
    }`,
})
export class EventAdminScoresPage {
  private readonly eventApi = inject(EventApi);

  protected readonly tab = signal<Tab>("SCOREBOARD");
  protected readonly scoreboard = new RemoteData(() => this.eventApi.scoreboard());
  protected readonly finalBoard = new RemoteData(() => this.eventApi.finalBoard());
  protected readonly log = new RemoteData(() => this.eventApi.adminDeductions());
  protected readonly download = new ActionState();

  constructor() {
    reloadOnRevisionChange(() => this.reload());
    this.reload();
    const interval = setInterval(() => this.reload(), REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(interval));
  }

  protected reload(): void {
    void this.scoreboard.load();
    void this.finalBoard.load();
    void this.log.load();
  }

  protected downloadCsv(): void {
    void this.download.run(() => this.eventApi.downloadExportCsv(), "CSV downloaded.");
  }
}
