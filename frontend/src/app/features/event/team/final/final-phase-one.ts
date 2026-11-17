import { ChangeDetectionStrategy, Component, computed, input, output, viewChild } from "@angular/core";
import {
  FinalActionResult,
  FinalCaseClue,
  FinalKeyIndexEntry,
  PassphraseRequest,
  TerminalBundleInfo,
} from "../../../../core/event/event-models";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { PendingAttempt } from "../pending-attempt";
import { EventFinalClueCard } from "./final-clue-card";
import { EventFinalCommand } from "./final-command";
import { EventFinalGuide } from "./final-guide";
import { EventFinalTerminal, TerminalSender } from "./final-terminal";

const KEY_FILES = ["key_A.pem", "key_B.pem", "key_C.pem", "key_D.pem"];
/** The one command every clue starts from: the case folder and a folder for the team's work. */
const FIRST_STEP = "cd ~/DevFest-Crypto && mkdir -p participant_test";

/**
 * Phase 1 — Decryption. Left: the first step, the progress, the Key Index and one card per clue with its steps (and a
 * collapsed "How it works"). Right: the terminal, which stays in view on a laptop while the cards scroll. Stacked on
 * phones.
 */
@Component({
  selector: "event-final-phase-one",
  imports: [EventFinalClueCard, EventFinalCommand, EventFinalGuide, EventFinalTerminal, UiCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: var(--space-4);
      align-items: start;
    }
    /* The terminal column takes the 80-column terminal's own width; the cards get the rest (at least 20rem). Only
       when even that does not fit does the terminal scroll sideways inside its frame. */
    @media (min-width: 1000px) {
      :host {
        grid-template-columns: minmax(20rem, 1fr) minmax(0, max-content);
      }
      .column--terminal {
        position: sticky;
        top: 5rem;
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
    .first {
      display: grid;
      gap: var(--space-2);
    }
    .first p {
      margin: 0;
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    .progress {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-3);
      margin: 0;
      font-weight: var(--font-weight-semibold);
    }
    .progress__count {
      font-family: var(--font-mono);
      color: var(--color-primary-text);
    }
    .dots {
      display: flex;
      gap: var(--space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .dot {
      inline-size: 12px;
      block-size: 12px;
      background: var(--color-surface-subtle);
      border: var(--border-outline-thin);
      border-radius: 50%;
    }
    .dot--done {
      background: var(--color-success);
      border-color: var(--color-success);
    }
    .keys {
      display: grid;
      gap: var(--space-3);
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
    td {
      font-family: var(--font-mono);
    }
    .pems {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .pems li {
      padding: 2px var(--space-2);
      font-family: var(--font-mono);
      font-size: var(--font-size-sm);
      background: var(--color-surface-subtle);
      border: var(--border-card);
      border-radius: var(--radius-sm);
    }
    .keys__label {
      margin: 0;
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    .clues {
      display: grid;
      gap: var(--space-4);
    }
    summary {
      min-block-size: 32px;
      font-weight: var(--font-weight-semibold);
      cursor: pointer;
    }
    details[open] > summary {
      margin-block-end: var(--space-3);
    }
  `,
  template: `<div class="column">
      <ui-card data-first-step>
        <h2 ui-card-header>Start here</h2>
        <div class="first">
          <p>Open the case folder and make a folder for your work. Do this once.</p>
          <event-final-command [command]="firstStep" [send]="send" />
        </div>
      </ui-card>
      <div class="progress" data-decrypted-progress>
        <span>Decrypted <span class="progress__count" data-decrypted-count>{{ solved() }} / 4</span></span>
        <ul class="dots" aria-hidden="true">
          @for (clue of clues(); track clue.number) {
            <li class="dot" [class.dot--done]="clue.solved"></li>
          }
        </ul>
      </div>
      <ui-card>
        <h2 ui-card-header>Key Index</h2>
        <div class="keys">
          <table data-key-index>
            <thead>
              <tr><th scope="col">Reference</th><th scope="col">Key</th></tr>
            </thead>
            <tbody>
              @for (entry of keyIndex(); track entry.reference) {
                <tr><td>{{ entry.reference }}</td><td>{{ entry.key }}</td></tr>
              }
            </tbody>
          </table>
          <p class="keys__label">RSA keys in the package</p>
          <ul class="pems" data-rsa-keys>
            @for (file of keyFiles; track file) {
              <li>{{ file }}</li>
            }
          </ul>
        </div>
      </ui-card>
      <div class="clues">
        @for (clue of clues(); track clue.number) {
          <event-final-clue-card
            [clue]="clue"
            [attempt]="attempts[clue.number - 1]"
            [send]="send"
            (changed)="changed.emit()"
          />
        }
      </div>
      <ui-card>
        <details data-how-it-works>
          <summary>How it works</summary>
          <event-final-guide />
        </details>
      </ui-card>
    </div>
    <div class="column column--terminal">
      <event-final-terminal [bundle]="terminal()" [ended]="ended()" />
    </div>`,
})
export class EventFinalPhaseOne {
  readonly clues = input.required<readonly FinalCaseClue[]>();
  readonly keyIndex = input.required<readonly FinalKeyIndexEntry[]>();
  readonly terminal = input<TerminalBundleInfo | null>(null);
  readonly ended = input(false);
  readonly changed = output<void>();

  protected readonly firstStep = FIRST_STEP;
  protected readonly keyFiles = KEY_FILES;
  protected readonly solved = computed(() => this.clues().filter((clue) => clue.solved).length);
  /** One stored attempt per file, so a retry after a lost response resends exactly that file's passphrase. */
  protected readonly attempts = ([1, 2, 3, 4] as const).map(
    (n) => new PendingAttempt<PassphraseRequest, FinalActionResult>(`clue-${n}`),
  );

  private readonly terminalView = viewChild(EventFinalTerminal);
  /** Typing a command into the terminal; the terminal answers why when it cannot. */
  protected readonly send: TerminalSender = (text) => this.terminalView()?.send(text) ?? "starting";
}
