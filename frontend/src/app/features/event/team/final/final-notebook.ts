import { ChangeDetectionStrategy, Component, input } from "@angular/core";
import { FinalNotebookEntry } from "../../../../core/event/event-models";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";

/**
 * The case notebook: every piece of evidence revealed so far stays reviewable, with no hint about the culprit. A
 * signed report (E03, E04) shows S, e, n and H and, once given, the team's verdict. The verdict itself is asked in
 * the main column (see EventFinalVerdicts); the notebook never says whether a verdict was right.
 */
@Component({
  selector: "event-final-notebook",
  imports: [UiCard],
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
  `,
  template: `<ui-card data-final-notebook>
    <h2 ui-card-header>Case notebook</h2>
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
              @if (!entry.verdictOpen && entry.yourVerdict) {
                <span class="event-muted" data-your-verdict>Your verdict: {{ entry.yourVerdict }}</span>
              }
            }
          </li>
        }
      </ol>
    }
  </ui-card>`,
})
export class EventFinalNotebook {
  readonly entries = input.required<readonly FinalNotebookEntry[]>();
}
