import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  signal,
} from "@angular/core";
import { UiStatusChip } from "../../shared/ui/ui-status-chip/ui-status-chip";
import {
  COUNTDOWN_WARNING_SECONDS,
  PHASE_LABELS,
  countdownLabel,
  effectivePhase,
  formatMinutesSeconds,
  remainingSeconds,
} from "../event/countdown";
import { EventLiveStore } from "../event/event-live-store";
import { PHASE_VARIANTS } from "../event/event-status";
import { roundStatusLabel } from "../event/format";

/**
 * Sticky event status for organizers and room managers: round, phase, a large countdown, the final and
 * (organizers) whether results are published. It shows the last board a page loaded (EventLiveStore) and
 * only ticks locally; it never fetches. "Last known" marks data older than the latest revision.
 */
@Component({
  selector: "app-event-status-bar",
  imports: [UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="status" aria-label="Event status" role="group">
      @if (view(); as v) {
        <strong class="status__round">{{ v.round }}</strong>
        @if (v.phase; as phase) {
          @if (!hidePhase()) {
          <ui-status-chip [variant]="phaseVariants[phase]">{{
            phaseLabels[phase]
          }}</ui-status-chip>
          }
        }
        @if (v.digits; as digits) {
          <span
            class="status__clock"
            role="timer"
            [class.status__clock--warning]="v.warning"
            [class.status__clock--expired]="v.phase === 'TIME_UP'"
            [attr.aria-label]="v.label"
            >{{ digits }}</span
          >
        }
        @if (final(); as chip) {
          <ui-status-chip class="status__extra" [variant]="chip.variant">{{
            chip.label
          }}</ui-status-chip>
        }
        @if (showResults() && published() !== null) {
          <ui-status-chip
            class="status__extra"
            [variant]="published() ? 'success' : 'neutral'"
            >{{ published() ? "Results published" : "Results not published" }}</ui-status-chip
          >
        }
        @if (store.stale()) {
          <span class="status__stale">Last known</span>
        }
      } @else {
        <span class="status__stale" role="status">Loading the round status…</span>
      }
    </div>
    <ng-content />`,
  styles: `
    :host {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      min-block-size: 56px;
      padding: var(--space-2) var(--space-8);
      background: var(--color-surface-glass);
      border-block-end: 1px solid var(--color-border);
      backdrop-filter: blur(6px);
    }
    .status {
      display: flex;
      flex: 1;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2) var(--space-3);
      min-inline-size: 0;
    }
    .status__round {
      font-size: var(--font-size-base);
      letter-spacing: var(--letter-spacing-heading);
    }
    .status__clock {
      padding: 0 var(--space-3);
      font-size: 1.5rem;
      font-weight: var(--font-weight-bold);
      font-variant-numeric: tabular-nums;
      line-height: 1.3;
      background: var(--color-surface);
      border: var(--border-outline-thin);
      border-radius: var(--radius-full);
    }
    .status__clock--warning {
      background: var(--color-warning-subtle);
      box-shadow: inset 0 0 0 3px var(--color-warning);
    }
    .status__clock--expired {
      color: var(--color-error);
      background: var(--color-error-subtle);
    }
    .status__stale {
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    @media (max-width: 800px) {
      :host {
        min-block-size: 48px;
        padding: var(--space-2) var(--space-4);
      }
      .status__clock {
        font-size: 1.2rem;
      }
    }
    @media (max-width: 600px) {
      .status__extra {
        display: none;
      }
    }
  `,
})
export class EventStatusBar {
  protected readonly store = inject(EventLiveStore);
  /** Organizers also see whether results are published. */
  readonly showResults = input(false);
  /** The organizer's Control page shows the phase next to its timer buttons, so the bar leaves it out there. */
  readonly hidePhase = input(false);

  protected readonly phaseLabels = PHASE_LABELS;
  protected readonly phaseVariants = PHASE_VARIANTS;
  protected readonly final = this.store.finalStatus;
  protected readonly published = this.store.resultsPublished;
  private readonly now = signal(Date.now());

  protected readonly view = computed(() => {
    const received = this.store.clock();
    if (!received) return null;
    const seconds = remainingSeconds(received.clock, received.receivedAt, this.now());
    const phase = effectivePhase(received.clock, seconds);
    return {
      round: roundStatusLabel(received.clock.currentRound),
      phase,
      digits: seconds === null ? null : formatMinutesSeconds(seconds),
      label: countdownLabel(phase, seconds),
      warning:
        phase === "RUNNING" &&
        seconds !== null &&
        seconds < COUNTDOWN_WARNING_SECONDS,
    };
  });

  constructor() {
    const tick = () => this.now.set(Date.now());
    const timer = setInterval(tick, 1000);
    document.addEventListener("visibilitychange", tick);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", tick);
    });
  }
}
