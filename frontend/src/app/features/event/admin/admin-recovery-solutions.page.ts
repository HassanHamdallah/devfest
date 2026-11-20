import { ChangeDetectionStrategy, Component, inject } from "@angular/core";
import { RouterLink } from "@angular/router";
import { EventApi } from "../../../core/event/event-api";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { RemoteData } from "./shared/remote-data";

/**
 * /event/admin/final-recovery-solutions: every ML answer, the Arduino level's bug list with the fixed lines, the right
 * option and why each wrong option fails. Organizers only; the server builds it from the imported content, so it is
 * exactly what the checks accept. Linked from Final reports; has a print layout.
 */
@Component({
  imports: [RouterLink, UiAlert, UiButton, UiCard, UiPageState, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .stack {
      display: grid;
      gap: var(--space-4);
    }
    h2 {
      margin: 0 0 var(--space-2);
      font-size: var(--font-size-lg);
    }
    h3 {
      margin: 0;
      font-size: var(--font-size-base);
    }
    p,
    ul {
      margin: 0;
    }
    ul {
      display: grid;
      gap: var(--space-1);
      padding-inline-start: var(--space-5);
    }
    .answer {
      display: grid;
      gap: var(--space-1);
      margin: var(--space-2) 0;
      padding: var(--space-3) var(--space-4);
      list-style: none;
      background: var(--color-success-subtle);
      border-radius: var(--radius-md);
    }
    .why {
      color: var(--color-text-muted);
    }
    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2) var(--space-3);
    }
    code,
    .code {
      font-family: var(--font-mono);
      font-size: var(--font-size-sm);
      overflow-wrap: anywhere;
    }
    .fix {
      display: grid;
      grid-template-columns: 3rem minmax(0, 1fr);
      gap: var(--space-1) var(--space-3);
      margin-block-start: var(--space-2);
    }
    .fix .n {
      color: var(--color-text-muted);
      text-align: end;
      font-variant-numeric: tabular-nums;
    }
    .old {
      color: var(--color-error);
      text-decoration: line-through;
    }
    .new {
      color: var(--brand-green-ink);
      font-weight: var(--font-weight-semibold);
    }
    pre.code {
      margin: var(--space-2) 0 0;
      padding: var(--space-3) var(--space-4);
      overflow-x: auto;
      background: var(--color-surface-subtle);
      border: var(--border-outline-thin);
      border-radius: var(--radius-md);
    }
    .option--right {
      border-color: var(--brand-green-ink);
      background: var(--color-success-subtle);
    }
    .option--wrong {
      border-color: var(--color-error);
    }
    .muted {
      color: var(--color-text-muted);
    }
    table {
      inline-size: 100%;
      border-collapse: collapse;
      font-size: var(--font-size-sm);
    }
    th,
    td {
      padding: var(--space-2);
      text-align: start;
      border-block-end: var(--border-card);
    }
    @media print {
      .no-print {
        display: none !important;
      }
      ui-card {
        break-inside: avoid;
        page-break-inside: avoid;
        box-shadow: none;
      }
    }
  `,
  template: `<header class="event-page-header">
      <h1>Recovery solutions</h1>
      <div class="event-page-header__actions no-print">
        <a routerLink="/event/admin/final-reports" class="event-link-button">Final reports</a>
        <ui-button variant="secondary" (click)="print()">Print</ui-button>
      </div>
    </header>
    <ui-alert variant="warning">Organizers only. These are the answers of the recovery levels: never show this page to a team.</ui-alert>
    <ui-page-state
      [loading]="solutions.loading()"
      loadingLabel="Loading the solutions…"
      [error]="solutions.blockingError()"
      (retry)="solutions.retry()"
    />
    @if (solutions.data(); as data) {
      <div class="stack" data-recovery-solutions>
        <h2>Which key opens which file</h2>
        <ui-card data-case-keys>
          <table>
            <thead>
              <tr><th scope="col">Clue</th><th scope="col">Encrypted file</th><th scope="col">RSA file</th><th scope="col">Key reference</th><th scope="col">Private key</th></tr>
            </thead>
            <tbody>
              @for (entry of data.caseKeys; track entry.clue) {
                <tr [attr.data-case-key]="entry.clue">
                  <td>{{ entry.clue }}</td>
                  <td class="code">{{ entry.encFile }}</td>
                  <td class="code">{{ entry.rsaFile }}</td>
                  <td class="code" data-case-key-reference>{{ entry.keyReference }}</td>
                  <td class="code" data-case-key-letter>{{ entry.key }}</td>
                </tr>
              }
            </tbody>
          </table>
        </ui-card>
        <h2>ML Forensic Audit</h2>
        @for (stage of data.ml; track stage.stage) {
          <ui-card [attr.data-ml-solution]="stage.stage">
            <div class="head">
              <h3>Stage {{ stage.stage }} — {{ stage.name }}</h3>
            </div>
            <ul class="answer" data-ml-answer>
              @for (line of stage.answer; track $index) {
                <li>{{ line }}</li>
              }
            </ul>
            @if (stage.why) {
              <p class="why">{{ stage.why }}</p>
            }
          </ui-card>
        }

        <h2>Arduino Debugging — {{ data.arduino.fileName }}</h2>
        @for (bug of data.arduino.bugs; track bug.id) {
          <ui-card [attr.data-bug]="bug.id">
            <div class="head">
              <h3>{{ bug.id }}</h3>
              <ui-status-chip variant="neutral">{{ kindLabel(bug.kind) }}</ui-status-chip>
            </div>
            @if (bug.what) {
              <p>{{ bug.what }}</p>
            }
            @if (bug.lines.length > 0) {
              <div class="fix" data-bug-lines>
                @for (line of bug.lines; track line.line) {
                  <span class="n">{{ line.line }}</span>
                  <span>
                    <code class="old" data-original>{{ line.original }}</code>
                    @if (bug.kind === 'REPLACE') {
                      <br />
                      <code class="new" data-fix>{{ line.fix }}</code>
                    } @else {
                      <span class="muted"> → {{ line.fix }}</span>
                    }
                  </span>
                }
              </div>
            }
            @if (bug.acceptedVariants.length > 1) {
              <p class="muted">Also accepted (whitespace ignored): <code>{{ bug.acceptedVariants.join('   ') }}</code></p>
            }
            @if (bug.failingTests.length > 0) {
              <p class="muted" data-bug-tests>Fails while it stays: {{ bug.failingTests.join('; ') }}</p>
            }
          </ui-card>
        }

        <h2>The missing behaviour (a choice after line {{ data.arduino.slot.afterLine }})</h2>
        <ui-card data-slot>
          <p class="code">{{ data.arduino.slot.prompt }}</p>
          <div class="stack" style="margin-block-start: var(--space-3)">
            @for (option of data.arduino.slot.options; track option.id) {
              <ui-card [class]="option.correct ? 'option--right' : 'option--wrong'" [attr.data-option]="option.id" [attr.data-correct]="option.correct">
                <div class="head">
                  <h3>Option {{ option.id }}</h3>
                  <ui-status-chip [variant]="option.correct ? 'success' : 'danger'">{{ option.correct ? "Correct" : "Wrong" }}</ui-status-chip>
                </div>
                <pre class="code">{{ option.code }}</pre>
                @if (option.why) {
                  <p class="why" data-option-why>{{ option.why }}</p>
                }
              </ui-card>
            }
          </div>
          <p class="muted" style="margin-block-start: var(--space-3)">
            A team sees the four options lettered in its own order (A, B, C, D by position), so the letter a team picks is
            not the option id shown here.
          </p>
        </ui-card>

        <ui-card data-arduino-tests>
          <h3>The eight hidden tests</h3>
          <ul>
            @for (test of data.arduino.tests; track $index) {
              <li>{{ test }}</li>
            }
          </ul>
        </ui-card>
      </div>
    }`,
})
export class EventAdminRecoverySolutionsPage {
  private readonly eventApi = inject(EventApi);

  protected readonly solutions = new RemoteData(() => this.eventApi.recoverySolutions());

  constructor() {
    void this.solutions.load();
  }

  protected kindLabel(kind: string): string {
    return kind === "REPLACE" ? "Fix the line" : kind === "REMOVE" ? "Remove the logic" : "Choose the option";
  }

  protected print(): void {
    window.print();
  }
}
