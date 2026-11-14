import {
  ChangeDetectionStrategy,
  Component,
  input,
  output,
} from "@angular/core";
import { RoundPhase } from "../../../../core/event/event-models";
import { newIdempotencyKey } from "../../../../core/event/pending-request";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { ControlAction } from "./control-actions";

/**
 * The timer buttons, always in the same order: the phase's primary action (Start timer / Pause / Resume),
 * +1 min, +5 min and Adjust…; then, set apart, End round (danger) and Next round. Rounds 0 and 5 only
 * offer Next round.
 */
@Component({
  selector: "app-round-timer-actions",
  imports: [UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="timer-actions" role="group" aria-label="Round timer">
    @if (phase(); as current) {
      <div class="timer-actions__frequent">
        @switch (current) {
          @case ("ASSIGNED") {
            <ui-button size="lg" [disabled]="busy()" (click)="start.emit()"
              >Start timer</ui-button
            >
          }
          @case ("RUNNING") {
            <ui-button
              size="lg"
              [disabled]="busy()"
              (click)="action.emit({ kind: 'pauseTimer' })"
              >Pause</ui-button
            >
          }
          @case ("PAUSED") {
            <ui-button
              size="lg"
              [disabled]="busy()"
              (click)="action.emit({ kind: 'resumeTimer' })"
              >Resume</ui-button
            >
          }
        }
        @if (current !== "ASSIGNED") {
          <ui-button variant="secondary" [disabled]="busy()" (click)="add(60)"
            >+1 min</ui-button
          >
          <ui-button variant="secondary" [disabled]="busy()" (click)="add(300)"
            >+5 min</ui-button
          >
        }
        <ui-button variant="ghost" [disabled]="busy()" (click)="adjust.emit()"
          >Adjust…</ui-button
        >
      </div>
    }
    <div class="timer-actions__round">
      @if (phase() === "RUNNING" || phase() === "PAUSED") {
        <ui-button
          variant="danger"
          [disabled]="busy()"
          (click)="action.emit({ kind: 'endRound' })"
          >End round</ui-button
        >
      }
      @if (!nextRoundBlocked()) {
        <ui-button
          [variant]="phase() ? 'secondary' : 'primary'"
          [size]="phase() ? 'md' : 'lg'"
          [disabled]="busy()"
          (click)="nextRound.emit()"
          >Next round</ui-button
        >
      }
    </div>
  </div>`,
  styles: `
    .timer-actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3) var(--space-6);
    }
    .timer-actions__frequent,
    .timer-actions__round {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
    }
    /* End round and Next round sit apart from the frequent buttons, behind a divider. */
    .timer-actions__round {
      padding-inline-start: var(--space-6);
      border-inline-start: 1px solid var(--color-border);
    }
    .timer-actions__round:only-child {
      padding-inline-start: 0;
      border: 0;
    }
    @media (max-width: 800px) {
      .timer-actions__round {
        padding: var(--space-3) 0 0;
        border-inline-start: 0;
        border-block-start: 1px solid var(--color-border);
        inline-size: 100%;
      }
    }
  `,
})
export class RoundTimerActions {
  readonly phase = input.required<RoundPhase | null>();
  readonly busy = input(false);
  readonly nextRoundBlocked = input(false);
  readonly start = output<void>();
  readonly action = output<ControlAction>();
  readonly adjust = output<void>();
  readonly nextRound = output<void>();

  protected add(seconds: number): void {
    this.action.emit({
      kind: "adjustTimer",
      deltaSeconds: seconds,
      key: newIdempotencyKey(),
    });
  }
}
