import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { FinalActionResult, FinalCaseClue, PassphraseRequest } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiSelect, UiSelectOption } from "../../../../shared/ui/ui-select/ui-select";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { PendingAttempt } from "../pending-attempt";
import { EventFinalCommand } from "./final-command";
import { clueLabel } from "./final-decrypted";
import { EventFinalPassphrase } from "./final-passphrase";
import { EventFinalReport } from "./final-report";
import { TerminalSender } from "./final-terminal";

const KEYS: readonly UiSelectOption[] = ["A", "B", "C", "D"].map((letter) => ({ value: letter, label: `Key ${letter}` }));

/**
 * One clue as one card with its five steps (pick the key, recover the passphrase with RSA, decrypt the report with
 * AES, read it, prove it). The commands are filled in with this clue's number and files and the key the team picked.
 * A solved clue collapses to "Decrypted" and its report; once the decryption phase is over an unsolved one only says it
 * was not decrypted in time.
 */
@Component({
  selector: "event-final-clue-card",
  imports: [
    EventFinalCommand,
    EventFinalPassphrase,
    EventFinalReport,
    FormsModule,
    UiButton,
    UiCard,
    UiSelect,
    UiStatusChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
      min-inline-size: 0;
    }
    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
      margin-block-end: var(--space-3);
    }
    h3 {
      margin: 0;
      font-size: var(--font-size-lg);
    }
    dl {
      display: grid;
      grid-template-columns: max-content minmax(0, 1fr);
      gap: var(--space-1) var(--space-3);
      margin: 0 0 var(--space-4);
      font-size: var(--font-size-sm);
    }
    dt {
      color: var(--color-text-muted);
    }
    dd {
      margin: 0;
      font-family: var(--font-mono);
      overflow-wrap: anywhere;
    }
    .chip {
      padding: 2px var(--space-2);
      background: var(--color-surface-subtle);
      border: var(--border-card);
      border-radius: var(--radius-sm);
    }
    ol {
      display: grid;
      gap: var(--space-4);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .step {
      display: grid;
      grid-template-columns: 28px minmax(0, 1fr);
      gap: var(--space-1) var(--space-3);
    }
    .step__number {
      display: grid;
      place-items: center;
      inline-size: 28px;
      block-size: 28px;
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-bold);
      color: var(--color-on-primary);
      background: var(--color-primary);
      border-radius: 50%;
    }
    .step__body {
      display: grid;
      gap: var(--space-2);
      min-inline-size: 0;
    }
    h4 {
      margin: 0;
      font-size: var(--font-size-base);
    }
    .step__hint {
      margin: 0;
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    .solved {
      display: grid;
      gap: var(--space-3);
    }
    .solved__row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
    }
    .solved__name {
      font-family: var(--font-mono);
      font-size: var(--font-size-sm);
      overflow-wrap: anywhere;
    }
    .picker {
      max-inline-size: 14rem;
    }
  `,
  template: `<ui-card [attr.data-clue]="clue().number" [attr.aria-label]="label()">
    <div class="head">
      <h3>{{ label() }}</h3>
      @if (clue().solved) {
        <ui-status-chip variant="success" data-clue-status>Decrypted</ui-status-chip>
      } @else if (readOnly()) {
        <ui-status-chip variant="neutral" data-clue-status>Not decrypted in time</ui-status-chip>
      } @else {
        <ui-status-chip variant="neutral" data-clue-status>Locked</ui-status-chip>
      }
    </div>
    @if (clue().solved) {
      <div class="solved">
        <div class="solved__row">
          <span class="solved__name">{{ clue().title }}.txt</span>
          <ui-button variant="secondary" size="sm" data-view-report (click)="showing.set(!showing())">{{
            showing() ? "Hide report" : "View report"
          }}</ui-button>
        </div>
        @if (showing() && clue().report) {
          <event-final-report [clue]="clue()" />
        }
      </div>
    } @else if (readOnly()) {
      <p class="step__hint" data-clue-missed>The decryption phase is over, so this file stays locked.</p>
    } @else {
      <dl>
        <dt>File</dt>
        <dd>{{ clue().encFile }}</dd>
        <dt>RSA file</dt>
        <dd>{{ clue().rsaFile }}</dd>
        <dt>Key reference</dt>
        <dd><span class="chip" data-key-reference>{{ clue().keyReference }}</span></dd>
      </dl>
      <ol>
        <li class="step">
          <span class="step__number" aria-hidden="true">1</span>
          <div class="step__body">
            <h4>Pick the key</h4>
            <p class="step__hint">Look up {{ clue().keyReference }} in the Key Index above.</p>
            <div class="picker" data-key-picker>
              <ui-select
                label="Key letter"
                [name]="'key-' + clue().number"
                placeholder="Choose a key"
                [compact]="true"
                [options]="keys"
                [ngModel]="letter()"
                (ngModelChange)="letter.set($event)"
              />
            </div>
          </div>
        </li>
        <li class="step">
          <span class="step__number" aria-hidden="true">2</span>
          <div class="step__body">
            <h4>Recover the passphrase (RSA)</h4>
            <event-final-command
              data-step="rsa"
              [command]="rsaCommand()"
              [ready]="letter() !== ''"
              waiting="Pick the key in step 1 first."
              [send]="send()"
            />
          </div>
        </li>
        <li class="step">
          <span class="step__number" aria-hidden="true">3</span>
          <div class="step__body">
            <h4>Decrypt the report (AES)</h4>
            <event-final-command data-step="aes" [command]="aesCommand()" [send]="send()" />
          </div>
        </li>
        <li class="step">
          <span class="step__number" aria-hidden="true">4</span>
          <div class="step__body">
            <h4>Read it</h4>
            <event-final-command data-step="read" [command]="readCommand()" [send]="send()" />
          </div>
        </li>
        <li class="step">
          <span class="step__number" aria-hidden="true">5</span>
          <div class="step__body">
            <h4>Prove it</h4>
            <p class="step__hint">Show the passphrase, then paste it below and press Check.</p>
            <event-final-command data-step="prove" [command]="proveCommand()" [send]="send()" />
            <event-final-passphrase [clue]="clue()" [attempt]="attempt()" (changed)="changed.emit()" />
          </div>
        </li>
      </ol>
    }
  </ui-card>`,
})
export class EventFinalClueCard {
  readonly clue = input.required<FinalCaseClue>();
  /** The stored attempt of this clue (so a retry after a lost response resends the same passphrase). */
  readonly attempt = input.required<PendingAttempt<PassphraseRequest, FinalActionResult>>();
  readonly send = input<TerminalSender | null>(null);
  /** The decryption phase is over: a solved clue keeps its report, an unsolved one stays locked. */
  readonly readOnly = input(false);
  readonly changed = output<void>();

  protected readonly keys = KEYS;
  /** The key the team picked; nothing until they choose. */
  protected readonly letter = signal("");
  protected readonly showing = signal(false);
  protected readonly label = computed(() => clueLabel(this.clue().number));
  protected readonly rsaCommand = computed(() => {
    const { number, rsaFile } = this.clue();
    return (
      `openssl pkeyutl -decrypt -inkey participant_package/keys/key_${this.letter() || "?"}.pem ` +
      `-in participant_package/clues/clue${number}/${rsaFile} -out participant_test/secret_${number}.txt ` +
      "-pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256"
    );
  });
  protected readonly aesCommand = computed(() => {
    const { number, encFile } = this.clue();
    return (
      `openssl enc -d -aes-256-cbc -pbkdf2 -in participant_package/clues/clue${number}/${encFile} ` +
      `-out participant_test/report_${number}.txt -pass file:participant_test/secret_${number}.txt`
    );
  });
  protected readonly readCommand = computed(() => `cat participant_test/report_${this.clue().number}.txt`);
  protected readonly proveCommand = computed(() => `cat participant_test/secret_${this.clue().number}.txt`);

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private seenUnsolved = false;

  constructor() {
    // When a clue turns solved the form the team typed in disappears: focus moves to its "View report" button.
    effect(() => {
      const solved = this.clue().solved;
      untracked(() => {
        if (!solved) this.seenUnsolved = true;
        else if (this.seenUnsolved) {
          this.seenUnsolved = false;
          afterNextRender(
            () => this.host.nativeElement.querySelector<HTMLButtonElement>("[data-view-report] button")?.focus(),
            { injector: this.injector },
          );
        }
      });
    });
  }
}
