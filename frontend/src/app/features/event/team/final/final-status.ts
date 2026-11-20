import { ChangeDetectionStrategy, Component, computed, input } from "@angular/core";
import { FinalScreen, FinalStage, RoundClock, TeamFinal } from "../../../../core/event/event-models";
import { FINAL_STAGE_LABELS } from "../../../../core/event/format";
import { UiCountdown } from "../../../../shared/ui/ui-countdown/ui-countdown";
import { UiStatusChip, UiStatusChipVariant } from "../../../../shared/ui/ui-status-chip/ui-status-chip";

const STATUS_VARIANTS: Record<FinalStage, UiStatusChipVariant> = {
  LOCKED: "warning",
  RECOVERY: "accent",
  IN_FINAL: "accent",
  COMPLETED: "success",
};

/** The case's four steps, as the screens the server reports them. */
const STEPS: readonly { label: string; screens: readonly FinalScreen[] }[] = [
  { label: "Unlock", screens: ["UNLOCK"] },
  { label: "Mission", screens: ["MISSION"] },
  { label: "Case", screens: ["CASE"] },
  { label: "Submitted", screens: ["SUBMITTED"] },
];

/**
 * A compact sticky bar with the status chip and the final's one countdown, so the timer stays in view while the
 * page scrolls, then, while the case is on, the step the team is on.
 */
@Component({
  selector: "event-final-status",
  imports: [UiCountdown, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    /* The host adds no box, so the bar sticks within the whole page column instead of within its own height. */
    :host {
      display: contents;
    }
    .bar {
      position: sticky;
      top: 4px;
      z-index: var(--z-sticky);
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2) var(--space-3);
      padding: var(--space-2) var(--space-4);
      background: var(--color-surface);
      border: var(--border-card);
      border-radius: var(--radius-card);
      box-shadow: var(--shadow-card);
    }
    .bar__left {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-3);
    }
    .bar__phase {
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-semibold);
      color: var(--color-primary-text);
    }
    .bar__timer {
      display: flex;
      align-items: center;
      gap: var(--space-2);
    }
    .bar__timer-label {
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    .bar ui-countdown.ui-countdown-host--large {
      padding: 0 var(--space-3);
      font-size: 1.5rem;
      border-width: 1.5px;
    }
    @media (max-width: 480px) {
      .bar {
        padding-inline: var(--space-3);
      }
      .bar__clues {
        display: none;
      }
    }
    .steps {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .steps li {
      display: inline-flex;
      align-items: center;
      gap: var(--space-2);
      min-block-size: 32px;
      padding: 0 var(--space-3) 0 var(--space-1);
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-full);
    }
    .steps__number {
      display: grid;
      place-items: center;
      inline-size: 24px;
      block-size: 24px;
      font-weight: var(--font-weight-bold);
      background: var(--color-surface-subtle);
      border-radius: 50%;
    }
    .steps__done {
      color: var(--color-text);
    }
    .steps__done .steps__number {
      color: var(--color-surface);
      background: var(--brand-green-ink);
    }
    .steps__now {
      font-weight: var(--font-weight-bold);
      color: var(--color-text);
      background: var(--color-primary-subtle);
      border: var(--border-outline-thin);
    }
    .steps__now .steps__number {
      color: var(--color-on-primary);
      background: var(--color-primary);
    }
  `,
  template: `<div class="bar" data-final-bar>
      <div class="bar__left">
        <ui-status-chip [variant]="variant()" data-final-status>{{ label() }}</ui-status-chip>
        <span class="bar__clues event-muted">{{ final().cluesCollected }} of 4 clues</span>
        @if (final().phase === 'DECRYPTION') {
          <span class="bar__phase" data-final-phase-label>Phase 1 — Decryption</span>
          @if (final().phase1Minutes; as minutes) {
            <span class="bar__phase event-muted" data-estimated-time>Estimated time: {{ minutes }} min</span>
          }
        } @else if (final().phase === 'INVESTIGATION') {
          <span class="bar__phase" data-final-phase-label>Phase 2 — Investigation</span>
        }
      </div>
      @if (clock(); as c) {
        <div class="bar__timer" data-final-timer>
          <span class="bar__timer-label">Final time left</span>
          <ui-countdown [clock]="c" [large]="true" />
        </div>
      }
    </div>
    @if (current() >= 0) {
      <ol class="steps" aria-label="Case steps">
        @for (step of steps; track step.label; let index = $index) {
          <li
            [class.steps__done]="index < current()"
            [class.steps__now]="index === current()"
            [attr.aria-current]="index === current() ? 'step' : null"
          >
            <span class="steps__number" aria-hidden="true">{{ index + 1 }}</span>
            {{ step.label }}
          </li>
        }
      </ol>
    }`,
})
export class EventFinalStatus {
  readonly final = input.required<TeamFinal>();

  protected readonly steps = STEPS;
  protected readonly label = computed(() => FINAL_STAGE_LABELS[this.final().status]);
  protected readonly variant = computed(() => STATUS_VARIANTS[this.final().status]);
  protected readonly current = computed(() =>
    this.final().status === "RECOVERY" || this.final().status === "LOCKED"
      ? -1
      : STEPS.findIndex((step) => step.screens.includes(this.final().screen)),
  );
  /** The final's one timer in the shape the shared countdown reads; none until the final opens. */
  protected readonly clock = computed<RoundClock | null>(() => {
    const final = this.final();
    // A completed team no longer plays against the clock; the others still do.
    if (final.status === "COMPLETED" || final.timerPhase === null || final.remainingSeconds === null) return null;
    return {
      currentRound: 5,
      phase: final.timerPhase,
      remainingSeconds: final.remainingSeconds,
      durationSeconds: 2400,
      serverNow: final.serverNow,
    };
  });
}
