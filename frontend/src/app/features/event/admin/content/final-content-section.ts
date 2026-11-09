import { HttpErrorResponse } from "@angular/common/http";
import { ChangeDetectionStrategy, Component, inject, signal } from "@angular/core";
import { message } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import { FinalContentView } from "../../../../core/event/event-models";
import { formatDateTime } from "../../../../core/event/format";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { RemoteData } from "../shared/remote-data";

/** The server's reasons for a refused file (400 with `problems`), or none. */
function problemsOf(error: unknown): string[] {
  const problems: unknown = error instanceof HttpErrorResponse ? error.error?.problems : null;
  return Array.isArray(problems) ? problems.filter((p): p is string => typeof p === "string") : [];
}

/**
 * The crypto final's content file (docs/final/crypto-content.json): choose it, import it, and see why it was
 * refused, every problem at once. The file is read-only once the final opens. The page never shows the private
 * values; it shows only what the file says about itself.
 */
@Component({
  selector: "app-final-content-section",
  imports: [UiAlert, UiButton, UiCard, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .stack {
      display: grid;
      gap: var(--space-3);
    }
    ul.problems {
      margin: 0;
      padding-inline-start: var(--space-5);
    }
    input[type="file"] {
      max-inline-size: 100%;
    }
  `,
  template: `<ui-card data-final-content>
    <div ui-card-header class="event-inline">
      <h2>Final content file</h2>
      @if (view(); as v) {
        @if (v.imported) {
          <ui-status-chip variant="success">Imported</ui-status-chip>
        } @else {
          <ui-status-chip variant="warning">Not imported</ui-status-chip>
        }
        @if (v.readOnly) {
          <ui-status-chip variant="warning">Read-only</ui-status-chip>
        }
      }
    </div>
    <div class="stack">
      @if (view(); as v) {
        @if (v.imported) {
          <p>
            Imported {{ importedAt() }}. Full time points until {{ v.fullUntilMinutes }} active minutes.
          </p>
          @if (v.recoveryIsPlaceholder) {
            <ui-alert variant="warning" data-recovery-placeholder
              >The Recovery Challenge is still the placeholder text. Replace it in the file and import it again before the
              final opens.</ui-alert
            >
          }
        } @else {
          <p class="event-muted">
            Import the validated JSON file (docs/final/crypto-content.json) with the layers, the signed reports, the
            evidence, the six suspects and the Recovery Challenge. The final cannot open without it.
          </p>
        }
        @if (!v.readOnly) {
          <label>
            <span class="event-sr-only">Final content file</span>
            <input
              type="file"
              accept=".json,application/json"
              data-final-content-file
              (change)="choose($event)"
            />
          </label>
          @if (chosen(); as file) {
            <p class="event-muted">Chosen: {{ file.name }}</p>
            <ui-button [loading]="sending()" (click)="send()">Import this file</ui-button>
          }
        } @else {
          <ui-alert variant="info">Event content is read-only after the final opens.</ui-alert>
        }
      }
      @if (problems().length > 0) {
        <ui-alert variant="error" data-final-problems>
          {{ headline() }}
          <ul class="problems">
            @for (problem of problems(); track $index) {
              <li>{{ problem }}</li>
            }
          </ul>
        </ui-alert>
      } @else if (error(); as text) {
        <ui-alert variant="error">{{ text }}</ui-alert>
      }
      @if (done()) {
        <ui-alert variant="success" data-final-imported>The file was imported.</ui-alert>
      }
    </div>
  </ui-card>`,
})
export class FinalContentSection {
  private readonly eventApi = inject(EventApi);

  protected readonly data = new RemoteData(() => this.eventApi.finalContent());
  protected readonly view = this.data.data;
  protected readonly chosen = signal<{ name: string; text: string } | null>(null);
  protected readonly sending = signal(false);
  protected readonly problems = signal<string[]>([]);
  protected readonly headline = signal("");
  protected readonly error = signal<string | null>(null);
  protected readonly done = signal(false);

  constructor() {
    void this.data.load();
  }

  protected importedAt(): string {
    return formatDateTime(this.view()?.importedAt ?? null);
  }

  protected async choose(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    this.done.set(false);
    this.problems.set([]);
    this.error.set(null);
    this.chosen.set(file ? { name: file.name, text: await file.text() } : null);
  }

  protected async send(): Promise<void> {
    const file = this.chosen();
    if (!file || this.sending()) return;
    this.sending.set(true);
    this.problems.set([]);
    this.error.set(null);
    this.done.set(false);
    try {
      await this.eventApi.importFinalContent(file.text);
      this.chosen.set(null);
      this.done.set(true);
      await this.data.load();
    } catch (e) {
      this.problems.set(problemsOf(e));
      this.headline.set(message(e));
      if (this.problems().length === 0) this.error.set(message(e));
    } finally {
      this.sending.set(false);
    }
  }
}

export type { FinalContentView };
