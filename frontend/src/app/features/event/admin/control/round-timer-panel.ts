import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  signal,
} from "@angular/core";
import {
  PHASE_LABELS,
  effectivePhase,
  remainingSeconds,
} from "../../../../core/event/countdown";
import {
  AdminRoomCard,
  EventStateView,
} from "../../../../core/event/event-models";
import { PHASE_VARIANTS } from "../../../../core/event/event-status";
import { roundStatusLabel } from "../../../../core/event/format";
import { UiCountdown } from "../../../../shared/ui/ui-countdown/ui-countdown";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { ControlAction } from "./control-actions";
import { nextRoundBlockReason, nextRoundLabel } from "./control-view";
import { newIdempotencyKey } from "../../../../core/event/pending-request";
import { RoundAdjustDialog } from "./round-adjust-dialog";
import { RoundTimerActions } from "./round-timer-actions";

/** "AI manager · 14:05" */
function readyLine(by: string | null, at: string | null): string {
  const time = at
    ? new Date(at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
    : "";
  return [by, time].filter(Boolean).join(" · ");
}

/**
 * The round's timer (`data-round-timer`): round, phase chip, big countdown, the buttons the phase allows in a
 * fixed order (primary action, +1 min, +5 min, then End round and Next round apart from them), the rooms'
 * "All teams are here" strip, and a custom ± minutes dialog. Every button emits a ControlAction; the page
 * confirms and runs it. The phase is followed locally, so the buttons switch when the countdown reaches 0.
 */
@Component({
  selector: "app-round-timer-panel",
  imports: [RoundAdjustDialog, RoundTimerActions, UiCountdown, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "data-round-timer": "" },
  template: `<section class="round-timer" aria-labelledby="round-timer-heading">
      <div class="round-timer__head">
        <div class="round-timer__title">
          <h2 id="round-timer-heading">{{ roundLabel() }}</h2>
          @if (phase(); as current) {
            <ui-status-chip [variant]="phaseVariants[current]">{{
              phaseLabels[current]
            }}</ui-status-chip>
          }
        </div>
        <ui-countdown [clock]="state()" [large]="true" />
      </div>
      <app-round-timer-actions
        [phase]="phase()"
        [busy]="busy()"
        [nextRoundBlocked]="!!nextRoundReason()"
        (start)="start()"
        (action)="emit($event)"
        (adjust)="adjusting.set(true)"
        (nextRound)="nextRound()"
      />
      <p class="round-timer__hint event-muted">
        {{ nextRoundReason() ?? nextRoundHint() }}
      </p>
      @if (phase()) {
        <ul class="round-timer__rooms" aria-label="Room readiness">
          @for (room of readiness(); track room.code) {
            <li [attr.data-ready-room]="room.code" [class]="'room-tone--' + room.code">
              <span class="round-timer__dot" aria-hidden="true"></span>
              <strong>{{ room.name }}</strong>
              @if (room.ready) {
                <ui-status-chip variant="success">Ready ✓</ui-status-chip>
                <span class="event-muted">{{ room.line }}</span>
              } @else {
                <ui-status-chip>Waiting</ui-status-chip>
              }
            </li>
          }
        </ul>
      }
    </section>
    <app-round-adjust-dialog
      [open]="adjusting()"
      [busy]="busy()"
      (adjust)="applyAdjust($event)"
      (cancelled)="adjusting.set(false)"
    />`,
  styleUrl: "./round-timer-panel.css",
})
export class RoundTimerPanel {
  readonly state = input.required<EventStateView>();
  readonly rooms = input.required<readonly AdminRoomCard[]>();
  readonly startWarnings = input.required<readonly string[]>();
  /** Blocking alerts: starting asks for "Start anyway". */
  readonly startBlockers = input<readonly string[]>([]);
  readonly busy = input(false);
  readonly action = output<ControlAction>();

  protected readonly phaseLabels = PHASE_LABELS;
  protected readonly phaseVariants = PHASE_VARIANTS;
  protected readonly adjusting = signal(false);

  private readonly now = signal(Date.now());
  private readonly receivedAt = linkedSignal({
    source: this.state,
    computation: () => Date.now(),
  });

  protected readonly roundLabel = computed(() =>
    roundStatusLabel(this.state().currentRound),
  );
  /** The server's phase, switched to TIME_UP as soon as the local countdown reaches 0. */
  protected readonly phase = computed(() => {
    const state = this.state();
    return effectivePhase(
      state,
      remainingSeconds(state, this.receivedAt(), this.now()),
    );
  });
  protected readonly nextRoundReason = computed(() =>
    nextRoundBlockReason(this.state()),
  );
  protected readonly nextRoundHint = computed(() =>
    nextRoundLabel(this.state()),
  );
  protected readonly readiness = computed(() =>
    this.rooms().map((room) => ({
      code: room.code,
      name: room.name,
      ready: room.arrival?.ready ?? false,
      line: readyLine(room.arrival?.by ?? null, room.arrival?.at ?? null),
    })),
  );

  constructor() {
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected emit(action: ControlAction): void {
    if (!this.busy()) this.action.emit(action);
  }

  protected start(): void {
    const waiting = this.readiness()
      .filter((room) => !room.ready)
      .map((room) => room.name);
    this.emit({
      kind: "startTimer",
      waiting,
      warnings: [...this.startWarnings()],
      blockers: [...this.startBlockers()],
    });
  }

  /** Absolute current+1, so a double click cannot skip a round. */
  protected nextRound(): void {
    if (this.nextRoundReason() || this.busy()) return;
    const state = this.state();
    const timeLeft = state.phase === "RUNNING" || state.phase === "PAUSED";
    this.emit({
      kind: "setRound",
      round: state.currentRound + 1,
      secondsLeft: timeLeft ? state.remainingSeconds : null,
    });
  }

  protected applyAdjust(deltaSeconds: number): void {
    this.adjusting.set(false);
    this.emit({ kind: "adjustTimer", deltaSeconds, key: newIdempotencyKey() });
  }
}
