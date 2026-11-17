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

const NOTES_KEY = "devfest.final.notes";
const MAX_NOTES = 20000;

/**
 * Phase 2 — Investigation. On a laptop two columns: the recovered evidence (a report opens right under its row) and
 * the team's own notes on the left, the suspects sheet with a search on the right; the one suspect is chosen below.
 * Stacked on phones. The notes stay in this browser only. Submitting needs a confirmation and happens one time only;
 * the answer never says whether it was right.
 */
@Component({
  selector: "event-final-phase-two",
  imports: [
    EventFinalDecrypted,
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
      .accuse-card {
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
      padding: var(--space-3);
      background: var(--color-surface-subtle);
      border-radius: var(--radius-md);
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
    }
    .empty {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
      margin: 0;
      color: var(--color-text-muted);
    }
    .accuse {
      display: grid;
      gap: var(--space-3);
      max-inline-size: 40rem;
    }
    .accuse h2 {
      font-size: var(--font-size-xl);
      letter-spacing: 0.02em;
    }
  `,
  template: `<div class="column">
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
        <p class="sheet__hint">The reports refer to people by ID.</p>
        <ui-input
          label="Search suspects"
          type="search"
          name="search"
          placeholder="Name or ID"
          [compact]="true"
          [ngModel]="query()"
          (ngModelChange)="query.set($event)"
        />
        @if (shown().length > 0) {
          <ul class="suspects" data-suspects-sheet aria-label="Suspects">
            @for (suspect of shown(); track suspect.id) {
              <li class="suspect" [attr.data-suspect]="suspect.id">
                <span class="suspect__id">{{ suspect.id }}</span>
                <span class="suspect__who">
                  <span class="suspect__name">{{ suspect.name }}</span>
                  <span class="suspect__position">{{ suspect.position }}</span>
                </span>
                <p class="suspect__statement">{{ suspect.statement }}</p>
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
    <ui-card class="accuse-card">
      <form class="accuse" (ngSubmit)="ask()" data-accuse>
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
        @if (error(); as text) {
          <ui-alert variant="error" [dismissible]="true">{{ text }}</ui-alert>
        }
        <ui-button type="submit" size="lg" [fullWidth]="true" [disabled]="chosen() === null">Submit</ui-button>
      </form>
    </ui-card>
    <ui-confirm-dialog
      [open]="confirming()"
      title="Submit your final answer?"
      [message]="'Submit ' + (chosen()?.name ?? '') + ' as the kidnapper? This cannot be changed.'"
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
    return this.suspects().filter((s) => s.id.toLowerCase().includes(text) || s.name.toLowerCase().includes(text));
  });
  protected readonly notes = signal(this.readNotes());
  protected readonly solved = computed(() => this.clues().filter((clue) => clue.solved).length);
  protected readonly confirming = signal(false);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

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
      await this.eventApi.submitSuspect(suspect.id);
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.sending.set(false);
      this.confirming.set(false);
      this.changed.emit();
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
