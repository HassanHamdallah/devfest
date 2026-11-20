import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { Api, message } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import { FinalCaseClue, FinalSuspect } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { UiSelect, UiSelectOption } from "../../../../shared/ui/ui-select/ui-select";
import { UiTextarea } from "../../../../shared/ui/ui-textarea/ui-textarea";
import { EventFinalDecrypted } from "./final-decrypted";
import { EMPTY_DRAFT, EventFinalReportForm, ReportDraft, isOverLimit } from "./final-report-form";
import { EventFinalSuspectDetail } from "./final-suspect-detail";

const NOTES_KEY = "devfest.final.notes";
const REPORT_KEY = "devfest.final.report";
const MAX_NOTES = 20000;
/** Every control character except the new line. */
const CONTROLS = new RegExp("[" + String.fromCharCode(0) + "-" + String.fromCharCode(9) + String.fromCharCode(11) + "-" + String.fromCharCode(31) + String.fromCharCode(127) + "]", "g");

/**
 * Phase 2 — Investigation. On a laptop two columns: the recovered evidence (a report opens right under its row) and
 * the team's own notes on the left, the suspects sheet with a search on the right; the one suspect is chosen below.
 * Stacked on phones. A suspect row opens its full card. The notes stay in this browser only. Submitting needs a confirmation and happens one time only;
 * the answer never says whether it was right.
 */
@Component({
  selector: "event-final-phase-two",
  imports: [
    EventFinalDecrypted,
    EventFinalReportForm,
    EventFinalSuspectDetail,
    FormsModule,
    UiAlert,
    UiButton,
    UiCard,
    UiConfirmDialog,
    UiInput,
    UiSelect,
    UiTextarea,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: var(--space-4);
      align-items: start;
    }
    @media (min-width: 1000px) {
      :host {
        grid-template-columns: minmax(0, 2fr) minmax(0, 3fr);
      }
      .accuse-form {
        grid-column: 1 / -1;
      }
      .suspects {
        max-block-size: 40rem;
        overflow-y: auto;
      }
    }
    .column {
      display: grid;
      gap: var(--space-4);
      min-inline-size: 0;
    }
    h2 {
      margin: 0;
      font-size: var(--font-size-lg);
    }
    .count {
      font-family: var(--font-mono);
      color: var(--color-primary-text);
    }
    .sheet {
      display: grid;
      gap: var(--space-3);
    }
    .sheet__hint {
      margin: 0;
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    .suspects {
      display: grid;
      gap: var(--space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .suspect {
      display: grid;
      grid-template-columns: max-content minmax(0, 1fr);
      gap: var(--space-1) var(--space-3);
      inline-size: 100%;
      padding: var(--space-3);
      font: inherit;
      color: inherit;
      text-align: start;
      background: var(--color-surface-subtle);
      border: var(--border-card);
      border-radius: var(--radius-md);
      cursor: pointer;
    }
    .suspect:hover {
      background: var(--color-primary-subtle);
    }
    .suspect:focus-visible {
      outline: 2px solid var(--color-primary);
      outline-offset: 2px;
    }
    .suspect--chosen {
      border-color: var(--color-primary);
    }
    .suspect__chosen {
      margin-inline-start: auto;
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-bold);
      color: var(--color-primary-text);
    }
    .suspect__id {
      align-self: start;
      padding: 2px var(--space-2);
      font-family: var(--font-mono);
      font-weight: var(--font-weight-bold);
      color: var(--color-primary-text);
      background: var(--color-primary-subtle);
      border-radius: var(--radius-sm);
    }
    .suspect__who {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: 0 var(--space-2);
    }
    .suspect__name {
      font-weight: var(--font-weight-bold);
    }
    .suspect__position {
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    .suspect__statement {
      grid-column: 2;
      margin: 0;
      font-size: var(--font-size-sm);
      font-style: italic;
      color: var(--color-text-muted);
      overflow-wrap: anywhere;
    }
    .empty {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
      margin: 0;
      color: var(--color-text-muted);
    }
    .accuse-form {
      display: grid;
      gap: var(--space-4);
      max-inline-size: 40rem;
    }
    .accuse {
      display: grid;
      gap: var(--space-3);
    }
    .accuse h2 {
      font-size: var(--font-size-xl);
      letter-spacing: 0.02em;
    }
  `,
  template: `<div class="column">
      @if (solved() === 4) {
        <ui-alert variant="success" data-all-decrypted>All four files decrypted: the investigation is open.</ui-alert>
      }
      <event-final-decrypted
        [clues]="clues()"
        title="Recovered Evidence"
        hint="Open and review the reports you decrypted during Phase 1."
      />
      <ui-card>
        <ui-textarea
          label="My notes"
          name="notes"
          hint="Only on this device."
          [rows]="8"
          [ngModel]="notes()"
          (ngModelChange)="saveNotes($event)"
        />
      </ui-card>
    </div>
    <ui-card>
      <h2 ui-card-header>
        Suspects Sheet
        <span class="count"
          ><span data-suspect-count>{{ suspects().length }}</span></span
        >
      </h2>
      <div class="sheet">
        <p class="sheet__hint">The reports refer to people by ID. Open a row to see the full card.</p>
        <ui-input
          label="Search suspects"
          type="search"
          name="search"
          placeholder="Name, ID, position or statement"
          [compact]="true"
          [ngModel]="query()"
          (ngModelChange)="query.set($event)"
        />
        @if (shown().length > 0) {
          <ul class="suspects" data-suspects-sheet aria-label="Suspects">
            @for (suspect of shown(); track suspect.id) {
              <li [attr.data-suspect]="suspect.id">
                <button
                  type="button"
                  class="suspect"
                  [class.suspect--chosen]="suspect.id === chosenId()"
                  [attr.aria-label]="'Open ' + suspect.id + ' ' + suspect.name"
                  (click)="detail.set(suspect)"
                >
                  <span class="suspect__id">{{ suspect.id }}</span>
                  <span class="suspect__who">
                    <span class="suspect__name">{{ suspect.name }}</span>
                    <span class="suspect__position">{{ suspect.position }}</span>
                    @if (suspect.id === chosenId()) {
                      <span class="suspect__chosen" data-suspect-chosen>Selected</span>
                    }
                  </span>
                  <span class="suspect__statement">“{{ suspect.statement }}”</span>
                </button>
              </li>
            }
          </ul>
        } @else {
          <p class="empty" data-suspects-empty>
            No suspect matches "{{ query().trim() }}".
            <ui-button size="sm" variant="secondary" (click)="query.set('')">Clear search</ui-button>
          </p>
        }
      </div>
    </ui-card>
    <form class="accuse-form" (ngSubmit)="ask()" data-accuse>
      <ui-card>
        <div class="accuse">
          <h2>WHO KIDNAPPED NOOR?</h2>
          @if (solved() < 4) {
            <ui-alert variant="warning" data-time-points-note
              >You can still submit; time points need all four clues decrypted.</ui-alert
            >
          }
          <ui-select
            label="Select one suspect"
            name="suspect"
            placeholder="Select one suspect"
            [options]="options()"
            [ngModel]="chosenId()"
            (ngModelChange)="chosenId.set($event)"
          />
        </div>
      </ui-card>
      <event-final-report-form [value]="report()" (valueChange)="saveReport($event)" />
      @if (error(); as text) {
        <ui-alert variant="error" [dismissible]="true">{{ text }}</ui-alert>
      }
      <ui-button type="submit" size="lg" [fullWidth]="true" [disabled]="chosen() === null || overLimit()">Submit</ui-button>
    </form>
    <event-final-suspect-detail [suspect]="detail()" (chosen)="pick($event)" (closed)="detail.set(null)" />
    <ui-confirm-dialog
      [open]="confirming()"
      title="Submit your final answer?"
      [message]="confirmMessage()"
      confirmLabel="Submit"
      [busy]="sending()"
      (confirmed)="submit()"
      (cancelled)="confirming.set(false)"
    />`,
})
export class EventFinalPhaseTwo {
  private readonly eventApi = inject(EventApi);
  private readonly api = inject(Api);

  readonly clues = input.required<readonly FinalCaseClue[]>();
  readonly suspects = input.required<readonly FinalSuspect[]>();
  readonly changed = output<void>();

  protected readonly chosenId = signal("");
  protected readonly chosen = computed(() => this.suspects().find((s) => s.id === this.chosenId()) ?? null);
  protected readonly options = computed<UiSelectOption[]>(() =>
    this.suspects().map((s) => ({ value: s.id, label: `${s.id} — ${s.name}` })),
  );
  protected readonly query = signal("");
  /** The suspects whose name or ID contains what was typed (every suspect when nothing is typed). */
  protected readonly shown = computed(() => {
    const text = this.query().trim().toLowerCase();
    if (!text) return this.suspects();
    return this.suspects().filter((s) =>
      [s.id, s.name, s.position, s.statement].some((field) => field.toLowerCase().includes(text)),
    );
  });
  /** The suspect whose full card is open. */
  protected readonly detail = signal<FinalSuspect | null>(null);
  protected readonly notes = signal(this.readNotes());
  /** The report as typed; kept on this device until the answer is submitted. */
  protected readonly report = signal<ReportDraft>(this.readReport());
  protected readonly overLimit = computed(() => isOverLimit(this.report()));
  protected readonly reportEmpty = computed(() => !this.evidenceTexts().some((text) => text.length > 0));
  protected readonly confirmMessage = computed(
    () =>
      `Submit ${this.chosen()?.name ?? ""} as the kidnapper? This cannot be changed.` +
      (this.reportEmpty() ? " Your report is empty. Organizers use it to judge your reasoning." : ""),
  );
  protected readonly solved = computed(() => this.clues().filter((clue) => clue.solved).length);
  protected readonly confirming = signal(false);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  /** "Select as the kidnapper" only fills the select below; Submit and its confirmation stay as they were. */
  protected pick(suspect: FinalSuspect): void {
    this.chosenId.set(suspect.id);
    this.detail.set(null);
  }

  protected ask(): void {
    if (this.chosen()) this.confirming.set(true);
  }

  /** The notes are the team's own: kept in this browser per team, never sent anywhere. */
  protected saveNotes(text: string): void {
    const value = (text ?? "").slice(0, MAX_NOTES);
    this.notes.set(value);
    try {
      localStorage.setItem(this.notesKey(), value);
    } catch {
      // Storage may be blocked; the notes then last until the page is closed.
    }
  }

  protected async submit(): Promise<void> {
    const suspect = this.chosen();
    if (!suspect || this.sending()) return;
    this.sending.set(true);
    this.error.set(null);
    try {
      await this.eventApi.submitSuspect(suspect.id, this.reportBody());
      this.clearReport();
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.sending.set(false);
      this.confirming.set(false);
      this.changed.emit();
    }
  }

  /** The draft is saved on this device on every keystroke, per team. */
  protected saveReport(draft: ReportDraft): void {
    this.report.set(draft);
    try {
      localStorage.setItem(this.reportKey(), JSON.stringify(draft));
    } catch {
      // Storage may be blocked; the draft then lasts until the page is closed.
    }
  }

  private evidenceTexts(): string[] {
    const { evidence1, evidence2, evidence3, evidence4 } = this.report();
    return [evidence1, evidence2, evidence3, evidence4].map((text) => text.trim());
  }

  /** The filled boxes only, with control characters other than new lines replaced (the server refuses them). */
  private reportBody(): Record<string, string> {
    const body: Record<string, string> = {};
    for (const [box, text] of Object.entries(this.report())) {
      const clean = text.replace(CONTROLS, " ").trim();
      if (clean) body[box] = clean;
    }
    return body;
  }

  private clearReport(): void {
    try {
      localStorage.removeItem(this.reportKey());
    } catch {
      // Nothing to clear.
    }
  }

  private reportKey(): string {
    return `${REPORT_KEY}.${this.api.user()?.id ?? "team"}`;
  }

  private readReport(): ReportDraft {
    try {
      const saved = JSON.parse(localStorage.getItem(this.reportKey()) ?? "null") as Partial<ReportDraft> | null;
      if (!saved) return EMPTY_DRAFT;
      const text = (value: unknown) => (typeof value === "string" ? value : "");
      return {
        evidence1: text(saved.evidence1),
        evidence2: text(saved.evidence2),
        evidence3: text(saved.evidence3),
        evidence4: text(saved.evidence4),
        explanation: text(saved.explanation),
      };
    } catch {
      return EMPTY_DRAFT;
    }
  }

  private notesKey(): string {
    return `${NOTES_KEY}.${this.api.user()?.id ?? "team"}`;
  }

  private readNotes(): string {
    try {
      return localStorage.getItem(this.notesKey()) ?? "";
    } catch {
      return "";
    }
  }
}
