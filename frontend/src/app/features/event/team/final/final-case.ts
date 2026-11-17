import { ChangeDetectionStrategy, Component, computed, input, output, signal } from "@angular/core";
import { TeamFinal } from "../../../../core/event/event-models";
import { EventFinalPhaseOne } from "./final-phase-one";
import { EventFinalPhaseTwo } from "./final-phase-two";

type Phase = 1 | 2;

const PHASE_KEY = "devfest.final.phase";

/**
 * The case: two tabs, "Phase 1 — Decryption" (open first) and "Phase 2 — Investigation", switchable at any time.
 * Both stay in the page while hidden, so the terminal keeps running when the team looks at Phase 2.
 */
@Component({
  selector: "event-final-case",
  imports: [EventFinalPhaseOne, EventFinalPhaseTwo],
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
        [attr.aria-selected]="phase() === 1"
        [attr.tabindex]="phase() === 1 ? 0 : -1"
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
        [attr.aria-selected]="phase() === 2"
        [attr.tabindex]="phase() === 2 ? 0 : -1"
        (click)="choose(2)"
        (keydown)="arrow($event)"
      >
        Phase 2 — Investigation
      </button>
    </div>
    <p class="hint">Suggested: Phase 1 about 15 minutes, Phase 2 about 25 minutes.</p>
    <div role="tabpanel" id="final-panel-1" aria-labelledby="final-tab-1" [hidden]="phase() !== 1" data-final-phase="1">
      <event-final-phase-one
        [clues]="final().clues"
        [keyIndex]="final().keyIndex"
        [terminal]="final().terminal"
        [ended]="ended()"
        (changed)="changed.emit()"
      />
    </div>
    <div role="tabpanel" id="final-panel-2" aria-labelledby="final-tab-2" [hidden]="phase() !== 2" data-final-phase="2">
      <event-final-phase-two
        [clues]="final().clues"
        [suspects]="final().suspects"
        (changed)="changed.emit()"
      />
    </div>`,
})
export class EventFinalCase {
  readonly final = input.required<TeamFinal>();
  readonly changed = output<void>();

  protected readonly phase = signal<Phase>(readPhase());
  protected readonly ended = computed(() => this.final().timerPhase === "TIME_UP");

  protected choose(phase: Phase): void {
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
    const next: Phase = this.phase() === 1 ? 2 : 1;
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
