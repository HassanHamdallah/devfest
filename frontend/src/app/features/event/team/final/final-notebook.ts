import { ChangeDetectionStrategy, Component, inject, input, output, signal } from "@angular/core";
import { message } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import { FinalNotebookEntry } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";

/**
 * The notebook: every piece of evidence revealed so far stays reviewable, with no hint about the culprit. A signed
 * report (E03, E04) shows S, e, n and H and takes one verdict, YES or NO, one time only. The panel never says
 * whether a verdict was right.
 */
@Component({
  selector: "event-final-notebook",
  imports: [UiAlert, UiButton, UiCard, UiConfirmDialog],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    ol {
      display: grid;
      gap: var(--space-3);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    li {
      display: grid;
      gap: var(--space-2);
      padding: var(--space-3);
      background: var(--color-surface-subtle);
      border-inline-start: 6px solid var(--color-primary);
      border-radius: var(--radius-sm);
    }
    .id {
      font-family: var(--font-mono, monospace);
      font-weight: var(--font-weight-bold);
    }
    .text {
      margin: 0;
      overflow-wrap: anywhere;
    }
    .signature {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2) var(--space-4);
      margin: 0;
      font-family: var(--font-mono, monospace);
    }
    .signature div {
      display: inline-flex;
      gap: var(--space-1);
    }
    .signature dt {
      color: var(--color-text-muted);
    }
    .signature dd {
      margin: 0;
      font-weight: var(--font-weight-bold);
    }
    .verdict {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
    }
  `,
  template: `<ui-card data-final-notebook>
    <h2 ui-card-header>Notebook</h2>
    @if (entries().length === 0) {
      <p class="event-muted">Evidence appears here as you open the layers.</p>
    } @else {
      <ol aria-label="Evidence">
        @for (entry of entries(); track entry.id) {
          <li [attr.data-evidence]="entry.id">
            <span class="id">{{ entry.id }}</span>
            <p class="text">{{ entry.text }}</p>
            @if (entry.claimedSigner) {
              <span class="event-muted">Claimed signer: {{ entry.claimedSigner }}</span>
            }
            @if (entry.signature; as sig) {
              <dl class="signature" aria-label="Signature values">
                <div><dt>S =</dt><dd>{{ sig.s }}</dd></div>
                <div><dt>e =</dt><dd>{{ sig.e }}</dd></div>
                <div><dt>n =</dt><dd>{{ sig.n }}</dd></div>
                <div><dt>H =</dt><dd>{{ sig.h }}</dd></div>
              </dl>
              @if (entry.verdictOpen) {
                <div class="verdict" [attr.data-verdict-for]="entry.id">
                  <span>Does this signature verify?</span>
                  <ui-button size="sm" variant="secondary" (click)="ask(entry, 'YES')">YES</ui-button>
                  <ui-button size="sm" variant="secondary" (click)="ask(entry, 'NO')">NO</ui-button>
                </div>
              } @else if (entry.yourVerdict) {
                <span class="event-muted" data-your-verdict>Your verdict: {{ entry.yourVerdict }}</span>
              }
            }
          </li>
        }
      </ol>
    }
    @if (error(); as text) {
      <ui-alert variant="error" [dismissible]="true">{{ text }}</ui-alert>
    }
    <ui-confirm-dialog
      [open]="pending() !== null"
      [title]="'Give your verdict on ' + (pending()?.entry?.id ?? '') + '?'"
      [message]="'You answer ' + (pending()?.verdict ?? '') + '. A verdict can be given only once and cannot be changed.'"
      confirmLabel="Give verdict"
      [busy]="sending()"
      (confirmed)="confirm()"
      (cancelled)="pending.set(null)"
    />
  </ui-card>`,
})
export class EventFinalNotebook {
  private readonly eventApi = inject(EventApi);

  readonly entries = input.required<readonly FinalNotebookEntry[]>();
  readonly changed = output<void>();

  protected readonly pending = signal<{ entry: FinalNotebookEntry; verdict: "YES" | "NO" } | null>(null);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  protected ask(entry: FinalNotebookEntry, verdict: "YES" | "NO"): void {
    this.error.set(null);
    this.pending.set({ entry, verdict });
  }

  protected async confirm(): Promise<void> {
    const choice = this.pending();
    if (!choice || this.sending()) return;
    const layer = choice.entry.id === "E03" ? 3 : 4;
    this.sending.set(true);
    try {
      await this.eventApi.giveVerdict(layer, choice.verdict);
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.sending.set(false);
      this.pending.set(null);
      this.changed.emit();
    }
  }
}
