import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
} from "@angular/core";
import { EventApi } from "../../../core/event/event-api";
import { reloadOnRevisionChange } from "../../../core/event/revision-sync";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiEmptyState } from "../../../shared/ui/ui-empty-state/ui-empty-state";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { RemoteData } from "../admin/shared/remote-data";
import { RankingTable } from "./ranking-table";

/**
 * Ranking for team representatives (once published) and organizers (always). A member's
 * 403 "Results are not published yet." is an expected state, not an error.
 */
@Component({
  imports: [UiAlert, UiEmptyState, UiPageState, UiStatusChip, RankingTable],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<header class="event-page-header">
      <h1>Results</h1>
      @if (publishedState(); as view) {
        <div class="event-page-header__meta">
          @if (view.published) {
            <ui-status-chip variant="success">Published</ui-status-chip>
          } @else {
            <ui-status-chip variant="warning">Not published</ui-status-chip>
            <span>Only organizers can see this ranking.</span>
          }
        </div>
      }
    </header>
    @if (notPublishedText(); as text) {
      <ui-empty-state
        [title]="text"
        description="Check back after the organizers publish the ranking."
      />
    } @else {
      <ui-page-state
        [loading]="results.loading()"
        loadingLabel="Loading results…"
        [error]="results.blockingError()"
        (retry)="results.retry()"
      />
      @if (results.staleError(); as error) {
        <ui-alert variant="warning"
          >Showing the last loaded ranking. {{ error }}</ui-alert
        >
      }
      @if (results.data(); as view) {
        <app-ranking-table [rows]="view.rows" />
      }
    }`,
  styles: `
    ui-alert {
      display: block;
      margin-bottom: var(--space-4);
    }
  `,
})
export class EventResultsPage {
  private readonly eventApi = inject(EventApi);
  protected readonly results = new RemoteData(() => this.eventApi.results());

  /** The server's 403 detail for a member while the ranking is private (also after an unpublish). */
  protected readonly notPublishedText = computed(() =>
    this.results.errorStatus() === 403 ? this.results.error() : null,
  );

  /** Header chip data; hidden while the member sees the not-published state. */
  protected readonly publishedState = computed(() =>
    this.notPublishedText() ? null : this.results.data(),
  );

  constructor() {
    reloadOnRevisionChange(() => this.reload());
    this.reload();
  }

  protected reload(): void {
    void this.results.load();
  }
}
