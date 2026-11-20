import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal, untracked } from "@angular/core";
import { TeamFinal } from "../../../../core/event/event-models";
import { decryptionClock } from "../../../../core/event/format";
import { UiPhaseClock } from "../../../../shared/ui/ui-phase-clock/ui-phase-clock";
import { EventFinalPhaseOne } from "./final-phase-one";
import { EventFinalPhaseTwo } from "./final-phase-two";

type Phase = 1 | 2;

const PHASE_KEY = "devfest.final.phase";
/** Set once the page has moved the team to Phase 2 by itself, so a refresh does not do it again. */
const SWITCHED_KEY = "devfest.final.switched";

/**
 * The case: two tabs, "Phase 1 — Decryption" (open first) and "Phase 2 — Investigation". The server decides the phase
 * from the final timer: during DECRYPTION the Phase 2 tab is locked and counts down to its opening; when the
 * INVESTIGATION phase starts the page moves to Phase 2 once, and Phase 1 stays readable but read-only. While hidden, the
 * tabs stay in the page, so the terminal keeps running when the team looks at the other one.
 */
@Component({
  selector: "event-final-case",
  imports: [EventFinalPhaseOne, EventFinalPhaseTwo, UiPhaseClock],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      gap: var(--space-3);
      min-inline-size: 0;
    }
    .tabs {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--space-2);
      padding: var(--space-1);
      background: var(--color-surface-subtle);
      border: var(--border-card);
      border-radius: var(--radius-full);
    }
    .tabs button {
      min-block-size: 44px;
      padding: 0 var(--space-3);
      font: inherit;
      font-weight: var(--font-weight-semibold);
      color: var(--color-text-muted);
      cursor: pointer;
      background: transparent;
      border: 0;
      border-radius: var(--radius-full);
    }
    .tabs button[aria-selected="true"] {
      color: var(--color-on-primary);
      background: var(--color-primary);
    }
    .tabs button[aria-disabled="true"] {
      cursor: not-allowed;
      opacity: 0.75;
    }
    .tabs button span {
      display: block;
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-regular);
    }
    .tabs button:focus-visible {
      outline: none;
      box-shadow: var(--focus-shadow);
    }
    .hint {
      margin: 0;
      font-size: var(--font-size-sm);
      text-align: center;
      color: var(--color-text-muted);
    }
    [hidden] {
      display: none !important;
    }
    @media (max-width: 480px) {
      .tabs {
        grid-template-columns: minmax(0, 1fr);
        border-radius: var(--radius-md);
      }
      .tabs button {
        border-radius: var(--radius-sm);
      }
    }
  `,
  template: `<div class="tabs" role="tablist" aria-label="Case phases" data-final-screen="CASE">
      <button
        type="button"
        role="tab"
        id="final-tab-1"
        aria-controls="final-panel-1"
        [attr.aria-selected]="current() === 1"
        [attr.tabindex]="current() === 1 ? 0 : -1"
        (click)="choose(1)"
        (keydown)="arrow($event)"
      >
        Phase 1 — Decryption
      </button>
      <button
        type="button"
        role="tab"
        id="final-tab-2"
        aria-controls="final-panel-2"
        [attr.aria-selected]="current() === 2"
        [attr.aria-disabled]="locked()"
        [attr.tabindex]="current() === 2 ? 0 : -1"
        data-phase-tab="2"
        (click)="choose(2)"
        (keydown)="arrow($event)"
      >
        Phase 2 — Investigation
        @if (locked()) {
          <span data-phase-locked>Opens at {{ opensAt() }} · <ui-phase-clock [clock]="phaseClock()" [left]="true" /></span>
        }
      </button>
    </div>
    <p class="hint" data-phase-hint>{{ hint() }}</p>
    <div role="tabpanel" id="final-panel-1" aria-labelledby="final-tab-1" [hidden]="current() !== 1" data-final-phase="1">
      <event-final-phase-one
        [clues]="final().clues"
        [keyIndex]="final().keyIndex"
        [terminal]="final().terminal"
        [ended]="ended()"
        [readOnly]="!locked()"
        (changed)="changed.emit()"
      />
    </div>
    @if (!locked()) {
      <div role="tabpanel" id="final-panel-2" aria-labelledby="final-tab-2" [hidden]="current() !== 2" data-final-phase="2">
        <event-final-phase-two
          [clues]="final().clues"
          [suspects]="final().suspects"
          (changed)="changed.emit()"
        />
      </div>
    }`,
})
export class EventFinalCase {
  readonly final = input.required<TeamFinal>();
  readonly changed = output<void>();

  protected readonly phase = signal<Phase>(readPhase());
  protected readonly ended = computed(() => this.final().timerPhase === "TIME_UP");
  /** The investigation has not opened yet: Phase 2 is locked and Phase 1 is where the team works. */
  protected readonly locked = computed(() => this.final().phase !== "INVESTIGATION");
  protected readonly current = computed<Phase>(() => (this.locked() ? 1 : this.phase()));
  protected readonly phaseClock = computed(() => decryptionClock(this.final()));
  protected readonly opensAt = computed(() => `${this.final().phase1Minutes ?? 15}:00`);
  protected readonly hint = computed(() =>
    this.locked()
      ? `Decrypt all four files. Phase 2 opens at ${this.opensAt()} of the final's time, or at once when the fourth file is decrypted.`
      : "The decryption phase is over. Phase 1 is read-only; use what you decrypted to choose the kidnapper.",
  );

  constructor() {
    // The one automatic move to Phase 2, when the server reports that the investigation has started.
    effect(() => {
      const open = this.final().phase === "INVESTIGATION";
      untracked(() => {
        if (open && !switched()) {
          markSwitched();
          this.choose(2);
        }
      });
    });
  }

  protected choose(phase: Phase): void {
    if (phase === 2 && this.locked()) return;
    this.phase.set(phase);
    try {
      sessionStorage.setItem(PHASE_KEY, String(phase));
    } catch {
      // The tab is a convenience only.
    }
  }

  protected arrow(event: KeyboardEvent): void {
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
    event.preventDefault();
    const next: Phase = this.current() === 1 ? 2 : 1;
    if (next === 2 && this.locked()) return;
    this.choose(next);
    document.getElementById(`final-tab-${next}`)?.focus();
  }
}

function readPhase(): Phase {
  try {
    return sessionStorage.getItem(PHASE_KEY) === "2" ? 2 : 1;
  } catch {
    return 1;
  }
}

function switched(): boolean {
  try {
    return sessionStorage.getItem(SWITCHED_KEY) === "1";
  } catch {
    return false;
  }
}

function markSwitched(): void {
  try {
    sessionStorage.setItem(SWITCHED_KEY, "1");
  } catch {
    // Without storage the page may move once more after a refresh; harmless.
  }
}
