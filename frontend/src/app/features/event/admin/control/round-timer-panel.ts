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
  FormControl,
  ReactiveFormsModule,
  Validators,
} from "@angular/forms";
import {
  PHASE_LABELS,
  effectivePhase,
  remainingSeconds,
} from "../../../../core/event/countdown";
import {
  AdminRoomCard,
  EventStateView,
  RoundPhase,
} from "../../../../core/event/event-models";
import { roundStatusLabel } from "../../../../core/event/format";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiCountdown } from "../../../../shared/ui/ui-countdown/ui-countdown";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { UiSelect } from "../../../../shared/ui/ui-select/ui-select";
import {
  UiStatusChip,
  UiStatusChipVariant,
} from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { ControlAction } from "./control-actions";

const PHASE_VARIANTS: Record<RoundPhase, UiStatusChipVariant> = {
  ASSIGNED: "neutral",
  RUNNING: "success",
  PAUSED: "warning",
  TIME_UP: "danger",
};

const ADJUST_DIRECTIONS = [
  { value: "add", label: "Add time" },
  { value: "remove", label: "Remove time" },
] as const;

/** "AI manager · 14:05" */
function readyLine(by: string | null, at: string | null): string {
  const time = at
    ? new Date(at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
    : "";
  return [by, time].filter(Boolean).join(" · ");
}

/**
 * The round's timer (`data-round-timer`): round, phase chip, big countdown, the buttons the phase allows, the
 * rooms' "All teams are here" strip, and a custom ± minutes dialog. Every button emits a ControlAction; the page
 * confirms and runs it. The phase is followed locally, so the buttons switch when the countdown reaches 0.
 */
@Component({
  selector: "app-round-timer-panel",
  imports: [
    ReactiveFormsModule,
    UiButton,
    UiConfirmDialog,
    UiCountdown,
    UiInput,
    UiSelect,
    UiStatusChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "data-round-timer": "" },
  template: `<section class="round-timer" aria-labelledby="round-timer-heading">
      <div class="round-timer__head">
        <h2 id="round-timer-heading">{{ roundLabel() }}</h2>
        @if (phase(); as current) {
          <ui-status-chip [variant]="phaseVariants[current]">{{
            phaseLabels[current]
          }}</ui-status-chip>
        }
        <ui-countdown [clock]="state()" [large]="true" />
      </div>
      @if (phase(); as current) {
        <div class="event-inline" role="group" aria-label="Round timer">
          @switch (current) {
            @case ("ASSIGNED") {
              <ui-button [disabled]="busy()" (click)="start()"
                >Start timer</ui-button
              >
            }
            @case ("RUNNING") {
              <ui-button
                variant="secondary"
                [disabled]="busy()"
                (click)="emit({ kind: 'pauseTimer' })"
                >Pause</ui-button
              >
            }
            @case ("PAUSED") {
              <ui-button
                [disabled]="busy()"
                (click)="emit({ kind: 'resumeTimer' })"
                >Resume</ui-button
              >
            }
          }
          @if (current !== "ASSIGNED") {
            <ui-button variant="secondary" [disabled]="busy()" (click)="add(60)"
              >+1 min</ui-button
            >
            <ui-button
              variant="secondary"
              [disabled]="busy()"
              (click)="add(300)"
              >+5 min</ui-button
            >
          }
          @if (current === "RUNNING" || current === "PAUSED") {
            <ui-button
              variant="danger"
              [disabled]="busy()"
              (click)="emit({ kind: 'endRound' })"
              >End round</ui-button
            >
          }
          <ui-button variant="ghost" [disabled]="busy()" (click)="openAdjust()"
            >Adjust…</ui-button
          >
        </div>
        <ul class="round-timer__rooms" aria-label="Room readiness">
          @for (room of readiness(); track room.code) {
            <li [attr.data-ready-room]="room.code">
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
    <ui-confirm-dialog
      [open]="adjusting()"
      title="Adjust the round time"
      message="Removing more time than is left ends the round (time is up)."
      [busy]="busy()"
      (confirmed)="applyAdjust()"
      (cancelled)="adjusting.set(false)"
    >
      <div class="round-timer__adjust">
        <ui-select
          label="Change"
          [options]="adjustDirections"
          [formControl]="direction"
        />
        <ui-input
          label="Minutes"
          type="number"
          hint="1 to 60 minutes."
          [error]="minutesError()"
          [formControl]="minutes"
        />
      </div>
    </ui-confirm-dialog>`,
  styles: `
    .round-timer {
      display: grid;
      gap: var(--space-3);
      padding: var(--space-4);
      background: var(--color-surface);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-card);
    }
    .round-timer__head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-3);
    }
    h2 {
      margin: 0;
      font-size: var(--font-size-lg);
    }
    .round-timer__rooms {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 200px), 1fr));
      gap: var(--space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .round-timer__rooms li {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
      font-size: var(--font-size-sm);
    }
    .round-timer__adjust {
      display: grid;
      gap: var(--space-3);
    }
  `,
})
export class RoundTimerPanel {
  readonly state = input.required<EventStateView>();
  readonly rooms = input.required<readonly AdminRoomCard[]>();
  readonly startWarnings = input.required<readonly string[]>();
  readonly busy = input(false);
  readonly action = output<ControlAction>();

  protected readonly phaseLabels = PHASE_LABELS;
  protected readonly phaseVariants = PHASE_VARIANTS;
  protected readonly adjustDirections = ADJUST_DIRECTIONS;
  protected readonly adjusting = signal(false);
  protected readonly direction = new FormControl<string>("add", {
    nonNullable: true,
  });
  protected readonly minutes = new FormControl("5", {
    nonNullable: true,
    validators: [
      Validators.required,
      Validators.pattern(/^\d+$/),
      Validators.min(1),
      Validators.max(60),
    ],
  });

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
    });
  }

  protected add(seconds: number): void {
    this.emit({
      kind: "adjustTimer",
      deltaSeconds: seconds,
      key: crypto.randomUUID(),
    });
  }

  protected openAdjust(): void {
    this.minutes.reset("5");
    this.direction.setValue("add");
    this.adjusting.set(true);
  }

  protected minutesError(): string | null {
    return this.minutes.touched && this.minutes.invalid
      ? "Enter whole minutes from 1 to 60."
      : null;
  }

  protected applyAdjust(): void {
    this.minutes.markAsTouched();
    if (this.minutes.invalid) return;
    const seconds = Number(this.minutes.value) * 60;
    this.adjusting.set(false);
    this.emit({
      kind: "adjustTimer",
      deltaSeconds: this.direction.value === "remove" ? -seconds : seconds,
      key: crypto.randomUUID(),
    });
  }
}
