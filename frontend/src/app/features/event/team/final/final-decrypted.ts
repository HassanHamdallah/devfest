import { ChangeDetectionStrategy, Component, computed, input, signal } from "@angular/core";
import { FinalCaseClue } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { EventFinalReport } from "./final-report";

/** "Clue 03" for clue 3. */
export function clueLabel(n: number): string {
  return `Clue ${String(n).padStart(2, "0")}`;
}

/**
 * The recovered evidence: "n / 4", each solved clue as REPORT.txt with View, whose report opens right under it; each
 * other one as locked. The report itself comes from the server only once the team decrypted that file.
 */
@Component({
  selector: "event-final-decrypted",
  imports: [EventFinalReport, UiButton, UiCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
      min-inline-size: 0;
    }
    h2 {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--space-2);
      margin: 0;
      font-size: var(--font-size-lg);
    }
    .count {
      font-family: var(--font-mono);
      color: var(--color-primary-text);
    }
    .hint {
      margin: 0 0 var(--space-3);
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    ul {
      display: grid;
      gap: var(--space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    li {
      display: grid;
      gap: var(--space-2);
      padding: var(--space-2) var(--space-3);
      background: var(--color-surface-subtle);
      border-radius: var(--radius-sm);
    }
    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
      min-block-size: 40px;
    }
    .name {
      display: grid;
      gap: 2px;
      min-inline-size: 0;
      overflow-wrap: anywhere;
    }
    .name strong {
      font-family: var(--font-mono);
      font-size: var(--font-size-sm);
    }
    .ok {
      font-size: var(--font-size-sm);
      color: var(--color-success);
    }
    .locked,
    .locked-hint {
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
  `,
  template: `<ui-card data-decrypted-files>
    <h2 ui-card-header>
      {{ title() }} <span class="count" data-decrypted-count>{{ solved() }} / 4</span>
    </h2>
    @if (hint(); as text) {
      <p class="hint">{{ text }}</p>
    }
    <ul>
      @for (clue of clues(); track clue.number) {
        <li [attr.data-decrypted-clue]="clue.number" [attr.data-solved]="clue.solved">
          <div class="row">
            @if (clue.solved) {
              <span class="name">
                <strong>{{ clue.title }}.txt</strong>
                <span class="ok">Successfully recovered</span>
              </span>
              <ui-button size="sm" variant="secondary" (click)="toggle(clue.number)">{{
                open().has(clue.number) ? "Hide" : "View"
              }}</ui-button>
            } @else {
              <span class="name locked">{{ label(clue.number) }} — Locked</span>
              <span class="locked-hint">Decrypt it in Phase 1 first.</span>
            }
          </div>
          @if (clue.solved && open().has(clue.number) && clue.report) {
            <event-final-report [clue]="clue" />
          }
        </li>
      }
    </ul>
  </ui-card>`,
})
export class EventFinalDecrypted {
  readonly clues = input.required<readonly FinalCaseClue[]>();
  readonly title = input("Recovered Evidence");
  readonly hint = input<string | null>(null);

  protected readonly open = signal<ReadonlySet<number>>(new Set());
  protected readonly solved = computed(() => this.clues().filter((clue) => clue.solved).length);
  protected readonly label = clueLabel;

  protected toggle(number: number): void {
    this.open.update((current) => {
      const next = new Set(current);
      if (!next.delete(number)) next.add(number);
      return next;
    });
  }
}
