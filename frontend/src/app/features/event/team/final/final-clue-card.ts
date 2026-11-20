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
import { FinalActionResult, FinalCaseClue, PassphraseRequest } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { PendingAttempt } from "../pending-attempt";
import { clueLabel } from "./final-decrypted";
import { EventFinalPassphrase } from "./final-passphrase";
import { EventFinalReport } from "./final-report";

/**
 * One clue as one card: its number, the .enc and .rsa file names, its key reference, its status and the "Recovered
 * passphrase" box. There are no ready-made commands: the team types them from the Command reference. A solved clue
 * collapses to "Decrypted" and its report. Nothing here is closed by time: the card stays until the final ends.
 */
@Component({
  selector: "event-final-clue-card",
  imports: [EventFinalPassphrase, EventFinalReport, UiButton, UiCard, UiStatusChip],
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
  `,
  template: `<ui-card [attr.data-clue]="clue().number" [attr.aria-label]="label()">
    <div class="head">
      <h3>{{ label() }}</h3>
      @if (clue().solved) {
        <ui-status-chip variant="success" data-clue-status>Decrypted</ui-status-chip>
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
    } @else {
      <dl>
        <dt>File</dt>
        <dd>{{ clue().encFile }}</dd>
        <dt>RSA file</dt>
        <dd>{{ clue().rsaFile }}</dd>
        <dt>Key reference</dt>
        <dd><span class="chip" data-key-reference>{{ clue().keyReference }}</span></dd>
      </dl>
      <event-final-passphrase [clue]="clue()" [attempt]="attempt()" (changed)="changed.emit()" />
    }
  </ui-card>`,
})
export class EventFinalClueCard {
  readonly clue = input.required<FinalCaseClue>();
  /** The stored attempt of this clue (so a retry after a lost response resends the same passphrase). */
  readonly attempt = input.required<PendingAttempt<PassphraseRequest, FinalActionResult>>();
  readonly changed = output<void>();

  protected readonly showing = signal(false);
  protected readonly label = computed(() => clueLabel(this.clue().number));
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
