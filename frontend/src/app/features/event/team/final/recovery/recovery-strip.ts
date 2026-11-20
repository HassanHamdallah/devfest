import { ChangeDetectionStrategy, Component, computed, input } from "@angular/core";
import { RecoveryLevel, RecoveryLevelProgress } from "../../../../../core/event/event-models";

type StepState = "current" | "done" | "locked";

interface StripStep {
  key: string;
  level: string;
  label: string;
  state: StepState;
}

const STATE_LABELS: Record<StepState, string> = { current: "Current", done: "Done", locked: "Locked" };

/**
 * The team's path as a row of levels: "ML Recovery", "Arduino Recovery" (only when the team must pass it), "Crypto" and
 * "Final Investigation", each current / done / locked, as in the prototype's progress row. It is built from what the server
 * says (the required levels and each level's passed flag), never from a guess.
 */
@Component({
  selector: "event-recovery-strip",
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    ol {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
      gap: var(--space-3);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    li {
      display: grid;
      gap: 2px;
      padding: var(--space-3) var(--space-4);
      background: var(--color-surface);
      border: var(--border-outline-thin);
      border-radius: var(--radius-md);
    }
    small {
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-semibold);
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--color-text-muted);
    }
    b {
      font-size: var(--font-size-base);
    }
    .state {
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    .done {
      background: var(--color-success-subtle);
      border-color: var(--brand-green-ink);
    }
    .done .state {
      color: var(--brand-green-ink);
      font-weight: var(--font-weight-semibold);
    }
    .current {
      background: var(--color-primary-subtle);
      border-color: var(--color-primary);
    }
    .current .state {
      color: var(--color-primary-text);
      font-weight: var(--font-weight-semibold);
    }
    .locked {
      opacity: 0.7;
    }
  `,
  template: `<ol aria-label="Your path through the final" data-recovery-strip>
    @for (step of steps(); track step.key) {
      <li [class]="step.state" [attr.data-step]="step.key" [attr.data-state]="step.state" [attr.aria-current]="step.state === 'current' ? 'step' : null">
        <small>{{ step.level }}</small>
        <b>{{ step.label }}</b>
        <span class="state">{{ stateLabel(step.state) }}</span>
      </li>
    }
  </ol>`,
})
export class EventRecoveryStrip {
  /** The levels the team must pass, in order. */
  readonly levels = input.required<readonly RecoveryLevel[]>();
  readonly progress = input.required<readonly RecoveryLevelProgress[]>();
  /** The recovery is over: Crypto is the current level. */
  readonly finished = input(false);

  protected readonly steps = computed<StripStep[]>(() => {
    const steps: StripStep[] = [];
    const first = this.progress().find((level) => !level.passed)?.level ?? null;
    for (const level of this.levels()) {
      const done = this.progress().some((p) => p.level === level && p.passed);
      steps.push({
        key: level.toLowerCase(),
        level: `Level ${String(steps.length + 1).padStart(2, "0")}`,
        label: level === "ML" ? "ML Recovery" : "Arduino Recovery",
        state: done ? "done" : level === first ? "current" : "locked",
      });
    }
    const crypto = String(steps.length + 1).padStart(2, "0");
    steps.push({ key: "crypto", level: `Level ${crypto}`, label: "Crypto", state: this.finished() ? "current" : "locked" });
    steps.push({
      key: "investigation",
      level: `Level ${String(steps.length + 1).padStart(2, "0")}`,
      label: "Final Investigation",
      state: "locked",
    });
    return steps;
  });

  protected stateLabel(state: StepState): string {
    return STATE_LABELS[state];
  }
}
