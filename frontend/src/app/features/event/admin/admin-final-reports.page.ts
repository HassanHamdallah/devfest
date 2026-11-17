import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from "@angular/core";
import { EventApi } from "../../../core/event/event-api";
import { FinalReportRow } from "../../../core/event/event-models";
import { reloadOnRevisionChange } from "../../../core/event/revision-sync";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { RemoteData } from "./shared/remote-data";

type Filter = "ALL" | "CORRECT" | "WRONG" | "NOT_SUBMITTED";

const FILTERS: readonly { value: Filter; label: string }[] = [
  { value: "ALL", label: "All" },
  { value: "CORRECT", label: "Correct" },
  { value: "WRONG", label: "Wrong" },
  { value: "NOT_SUBMITTED", label: "Not submitted" },
];

/** Reports change when teams submit; ask again while the page is open. */
const REFRESH_MS = 10000;

/**
 * /event/admin/final-reports: every team's chosen suspect (with Correct or Wrong, which a team never sees) and its Final
 * Investigation Report, with a filter and a print layout (one team per block, no application chrome). Organizers only.
 */
@Component({
  imports: [UiAlert, UiButton, UiCard, UiPageState, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .filters {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      margin-block-end: var(--space-4);
    }
    .list {
      display: grid;
      gap: var(--space-4);
    }
    .team {
      display: grid;
      gap: var(--space-3);
    }
    .team__head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2) var(--space-3);
    }
    .team__head h2 {
      margin: 0;
      font-size: var(--font-size-lg);
    }
    .team__when {
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    .team__suspect {
      margin: 0;
      font-weight: var(--font-weight-semibold);
    }
    .evidence {
      display: grid;
      gap: var(--space-2);
    }
    .text {
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
    .label {
      margin: 0;
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-bold);
      letter-spacing: 0.06em;
      color: var(--color-text-muted);
    }
    p {
      margin: 0;
    }
    .none {
      color: var(--color-text-muted);
    }
    @media print {
      .no-print {
        display: none !important;
      }
      .team {
        break-inside: avoid;
        page-break-inside: avoid;
        margin-block-end: 8mm;
        padding: 0 0 4mm;
        border-block-end: 1px solid #999;
        box-shadow: none;
      }
    }
  `,
  template: `<header class="event-page-header">
      <h1>Final reports</h1>
      <div class="event-page-header__actions no-print">
        <ui-button variant="secondary" (click)="print()">Print</ui-button>
      </div>
    </header>
    <div class="filters no-print" role="group" aria-label="Filter reports">
      @for (option of filters; track option.value) {
        <ui-button
          variant="secondary"
          [pressed]="filter() === option.value"
          [attr.data-filter]="option.value"
          (click)="filter.set(option.value)"
          >{{ option.label }}</ui-button
        >
      }
    </div>
    <ui-page-state
      [loading]="reports.loading()"
      loadingLabel="Loading the reports…"
      [error]="reports.blockingError()"
      (retry)="reports.retry()"
    />
    @if (reports.staleError(); as error) {
      <ui-alert variant="warning">Showing the last loaded reports. {{ error }}</ui-alert>
    }
    @if (reports.data()) {
      <div class="list" data-final-reports>
        @for (row of shown(); track row.teamId) {
          <ui-card class="team" [attr.data-team]="row.teamName" [attr.data-submitted]="row.submitted">
            <div class="team__head">
              <h2>{{ row.teamName }}</h2>
              @if (!row.submitted) {
                <ui-status-chip variant="neutral" data-verdict>Not submitted</ui-status-chip>
              } @else if (row.correct) {
                <ui-status-chip variant="success" data-verdict>Correct</ui-status-chip>
              } @else {
                <ui-status-chip variant="danger" data-verdict>Wrong</ui-status-chip>
              }
              @if (row.submitted) {
                <span class="team__when" data-submitted-at>Submitted at minute {{ minute(row) }}</span>
              }
            </div>
            @if (row.submitted) {
              <p class="team__suspect" data-suspect>{{ row.suspectId }} — {{ row.suspectName ?? row.suspectId }}</p>
              @if (evidence(row).length > 0) {
                <div class="evidence" aria-label="Evidence">
                  @for (item of evidence(row); track item.n) {
                    <div data-evidence>
                      <p class="label">EVIDENCE {{ item.n }}</p>
                      <p class="text">{{ item.text }}</p>
                    </div>
                  }
                </div>
              } @else {
                <p class="none" data-no-evidence>No evidence written.</p>
              }
              @if (row.explanation; as explanation) {
                <div>
                  <p class="label">EXPLANATION</p>
                  <p class="text" data-explanation>{{ explanation }}</p>
                </div>
              }
            } @else {
              <p class="none">No suspect submitted yet.</p>
            }
          </ui-card>
        } @empty {
          <p class="none" data-no-reports>No team matches this filter.</p>
        }
      </div>
    }`,
})
export class EventAdminFinalReportsPage {
  private readonly eventApi = inject(EventApi);

  protected readonly filters = FILTERS;
  protected readonly filter = signal<Filter>("ALL");
  protected readonly reports = new RemoteData(() => this.eventApi.finalReports());
  protected readonly shown = computed(() => {
    const rows = this.reports.data() ?? [];
    switch (this.filter()) {
      case "CORRECT":
        return rows.filter((row) => row.submitted && row.correct === true);
      case "WRONG":
        return rows.filter((row) => row.submitted && row.correct !== true);
      case "NOT_SUBMITTED":
        return rows.filter((row) => !row.submitted);
      default:
        return rows;
    }
  });

  constructor() {
    reloadOnRevisionChange(() => void this.reports.load());
    void this.reports.load();
    const interval = setInterval(() => void this.reports.load(), REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(interval));
  }

  /** The final's active time at the submission, as a whole minute (the submission's 12:30 is minute 12). */
  protected minute(row: FinalReportRow): string {
    return row.activeSeconds === null ? "—" : String(Math.floor(row.activeSeconds / 60));
  }

  /** The filled evidence boxes, keeping their numbers. */
  protected evidence(row: FinalReportRow): { n: number; text: string }[] {
    return [row.evidence1, row.evidence2, row.evidence3, row.evidence4]
      .map((text, index) => ({ n: index + 1, text }))
      .filter((item): item is { n: number; text: string } => item.text !== null);
  }

  protected print(): void {
    window.print();
  }
}
