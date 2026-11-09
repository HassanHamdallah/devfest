import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from "@angular/core";
import { EventApi } from "../../../../core/event/event-api";
import { FinalTimerView, RoundClock } from "../../../../core/event/event-models";
import { reloadOnRevisionChange } from "../../../../core/event/revision-sync";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCountdown } from "../../../../shared/ui/ui-countdown/ui-countdown";
import { UiStatusChip, UiStatusChipVariant } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { ActionState } from "../shared/action-state";
import { ActionStatus } from "../shared/action-status";

const PHASE_CHIPS: Record<string, { label: string; variant: UiStatusChipVariant }> = {
  RUNNING: { label: "Running", variant: "success" },
  PAUSED: { label: "Paused", variant: "warning" },
  TIME_UP: { label: "Time's up", variant: "danger" },
};

/** Refetch cadence while the final is open: a timer that runs out does not change the event revision. */
const REFRESH_MS = 5000;

/**
 * The final's one 40-minute timer, for every team at once: its countdown and the emergency controls (pause, resume,
 * add time). Each control is audited on the server and applies to all teams; nothing here is per team, and the timer is
 * never reset. Shown only while the final is open.
 */
@Component({
  selector: "app-final-timer-panel",
  imports: [ActionStatus, UiButton, UiCountdown, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "data-final-timer-panel": "" },
  styles: `
    section {
      display: grid;
      gap: var(--space-4);
      padding: var(--space-5) var(--space-6);
      background: var(--color-surface);
      border: var(--border-outline);
      border-radius: var(--radius-card);
    }
    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3) var(--space-5);
    }
    .title {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-3);
    }
    h2 {
      margin: 0;
      font-size: var(--font-size-xl);
    }
    .hint {
      margin: 0;
      font-size: var(--font-size-sm);
    }
  `,
  template: `@if (view(); as timer) {
    @if (timer.open) {
      <section aria-labelledby="final-timer-heading">
        <div class="head">
          <div class="title">
            <h2 id="final-timer-heading">Final timer</h2>
            @if (chip(); as c) {
              <ui-status-chip [variant]="c.variant" data-final-timer-phase>{{ c.label }}</ui-status-chip>
            }
          </div>
          @if (clock(); as c) {
            <ui-countdown [clock]="c" [large]="true" />
          }
        </div>
        <div class="event-inline">
          @if (timer.phase === "PAUSED") {
            <ui-button [disabled]="state.busy()" (click)="resume()">Resume final</ui-button>
          } @else {
            <ui-button variant="secondary" [disabled]="state.busy() || timer.phase !== 'RUNNING'" (click)="pause()"
              >Pause final</ui-button
            >
          }
          @for (minutes of addChoices; track minutes) {
            <ui-button
              variant="secondary"
              [disabled]="state.busy() || timer.phase === 'TIME_UP'"
              (click)="add(minutes)"
              >+{{ minutes }} min</ui-button
            >
          }
        </div>
        <app-action-status [state]="state" />
        <p class="hint event-muted">
          One timer for every team. Pause and added time apply to everyone at once and are recorded in the audit log.
        </p>
      </section>
    }
  }`,
})
export class FinalTimerPanel {
  private readonly eventApi = inject(EventApi);

  /** The admin board's final-open flag: the panel asks the server for the timer only while it is true. */
  readonly open = input.required<boolean>();

  protected readonly addChoices = [1, 5, 10];
  protected readonly state = new ActionState();
  protected readonly view = signal<FinalTimerView | null>(null);
  protected readonly chip = computed(() => {
    const phase = this.view()?.phase;
    return phase ? (PHASE_CHIPS[phase] ?? null) : null;
  });
  protected readonly clock = computed<RoundClock | null>(() => {
    const timer = this.view();
    if (!timer || timer.phase === null || timer.remainingSeconds === null) return null;
    return {
      currentRound: 5,
      phase: timer.phase,
      remainingSeconds: timer.remainingSeconds,
      durationSeconds: timer.durationSeconds,
      serverNow: timer.serverNow,
    };
  });

  constructor() {
    reloadOnRevisionChange(() => void this.load());
    effect(() => {
      if (this.open()) untracked(() => void this.load());
    });
    const interval = setInterval(() => {
      if (this.open()) void this.load();
    }, REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(interval));
  }

  protected pause(): void {
    void this.run(() => this.eventApi.pauseFinal(), "The final is paused for every team.");
  }

  protected resume(): void {
    void this.run(() => this.eventApi.resumeFinal(), "The final is running again.");
  }

  protected add(minutes: number): void {
    void this.run(
      () => this.eventApi.addFinalTime(minutes * 60, crypto.randomUUID()),
      `${minutes} minutes added for every team.`,
    );
  }

  private async run(work: () => Promise<FinalTimerView>, notice: string): Promise<void> {
    let result: FinalTimerView | null = null;
    const ok = await this.state.run(async () => {
      result = await work();
    }, notice);
    if (ok && result) this.view.set(result);
    else await this.load();
  }

  private async load(): Promise<void> {
    if (!this.open()) return;
    try {
      this.view.set(await this.eventApi.finalTimer());
    } catch {
      // The panel keeps the last timer it had; the control page reports its own load errors.
    }
  }
}
