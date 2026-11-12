import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
} from "@angular/core";
import { RouterLink } from "@angular/router";
import { EventApi } from "../../../../core/event/event-api";
import { PublicRankingRow, RankingRow } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { RankingTable } from "../../results/ranking-table";
import { ActionState } from "../shared/action-state";
import { ActionStatus } from "../shared/action-status";
import { ControlAction } from "./control-actions";

const TOP_TEAMS = 5;

/** The top of the live ranking (total out of 100) with a link to Scores, plus publication and CSV export. */
@Component({
  selector: "app-ranking-panel",
  imports: [ActionStatus, RankingTable, RouterLink, UiButton, UiStatusChip],
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
    <app-ranking-table [rows]="topLines()" />
    <p class="ranking-panel__more">
      <a routerLink="/event/admin/scores" data-all-scores>All scores →</a>
      <span class="event-muted">Top {{ topLines().length }} of {{ lines().length }} teams.</span>
    </p>
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
    .ranking-panel__more {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2) var(--space-4);
      margin: 0;
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
  /** The ranking in the shape of the shared table, keeping the team id so a name opens the team panel. */
  protected readonly lines = computed<(PublicRankingRow & { teamId: number })[]>(() =>
    this.rows().map((row) => ({
      rank: row.rank,
      teamId: row.scores.teamId,
      teamName: row.scores.teamName,
      groupCode: row.scores.groupCode,
      roomScores: row.scores.rooms.map((room) => room.score),
      finalScore: row.scores.finalScore.total,
      total: row.scores.total,
      finalStatus: row.scores.finalScore.status,
      disqualified: row.scores.disqualified,
    })),
  );

  /** Only the leaders show here; the Scores page has every team. */
  protected readonly topLines = computed(() => this.lines().slice(0, TOP_TEAMS));

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
