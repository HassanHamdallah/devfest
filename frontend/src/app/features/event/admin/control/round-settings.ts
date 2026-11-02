import {
  ChangeDetectionStrategy,
  Component,
  effect,
  input,
  output,
  untracked,
} from "@angular/core";
import { FormControl, ReactiveFormsModule, Validators } from "@angular/forms";
import { EventStateView } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { UiSelect } from "../../../../shared/ui/ui-select/ui-select";
import { ControlAction } from "./control-actions";
import { ROUND_OPTIONS } from "./control-view";

/** Explicit round (0..5, e.g. to correct a mistake) and the countdown length. */
@Component({
  selector: "app-round-settings",
  imports: [ReactiveFormsModule, UiButton, UiCard, UiInput, UiSelect],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<ui-card>
    <h2 ui-card-header>Round settings</h2>
    <div class="round-settings">
      <form aria-label="Set round" (submit)="applyRound($event)">
        <ui-select
          label="Set round"
          [options]="roundOptions"
          [formControl]="roundChoice"
        />
        <ui-button
          type="submit"
          variant="secondary"
          [disabled]="busy() || isCurrentRoundChosen()"
          >Apply round</ui-button
        >
      </form>
      <form aria-label="Round minutes" (submit)="saveMinutes($event)">
        <ui-input
          label="Round minutes"
          type="number"
          hint="Length of rounds that begin later; the current round keeps its time."
          [error]="minutesError()"
          [formControl]="minutes"
        />
        <ui-button type="submit" variant="secondary" [disabled]="busy()"
          >Save minutes</ui-button
        >
      </form>
    </div>
  </ui-card>`,
  styles: `
    h2 {
      margin: 0;
      font-size: var(--font-size-lg);
    }
    .round-settings {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 280px), 1fr));
      gap: var(--space-4) var(--space-6);
    }
    form {
      display: grid;
      align-content: start;
      justify-items: start;
      gap: var(--space-2);
    }
    ui-select,
    ui-input {
      justify-self: stretch;
    }
  `,
})
export class RoundSettings {
  readonly state = input.required<EventStateView>();
  readonly busy = input(false);
  readonly action = output<ControlAction>();

  protected readonly roundOptions = ROUND_OPTIONS;
  protected readonly roundChoice = new FormControl("0", { nonNullable: true });
  protected readonly minutes = new FormControl("25", {
    nonNullable: true,
    validators: [
      Validators.required,
      Validators.pattern(/^\d+$/),
      Validators.min(1),
      Validators.max(180),
    ],
  });

  constructor() {
    effect(() => {
      const state = this.state();
      untracked(() => this.syncControls(state));
    });
  }

  protected applyRound(event: Event): void {
    event.preventDefault();
    const round = Number(this.roundChoice.value);
    if (this.busy() || round === this.state().currentRound) return;
    this.roundChoice.markAsPristine();
    this.action.emit({ kind: "setRound", round });
  }

  protected saveMinutes(event: Event): void {
    event.preventDefault();
    this.minutes.markAsTouched();
    if (this.busy() || this.minutes.invalid) return;
    this.minutes.markAsPristine();
    this.action.emit({
      kind: "saveRoundMinutes",
      minutes: Number(this.minutes.value),
    });
  }

  protected minutesError(): string | null {
    return this.minutes.touched && this.minutes.invalid
      ? "Enter whole minutes from 1 to 180."
      : null;
  }

  protected isCurrentRoundChosen(): boolean {
    return Number(this.roundChoice.value) === this.state().currentRound;
  }

  /**
   * Server values replace the controls unless the organizer is mid-edit. A pending round choice that the event
   * has since reached is no longer an edit, so the select follows the board again.
   */
  private syncControls(state: EventStateView): void {
    const current = String(state.currentRound);
    if (this.roundChoice.value === current) this.roundChoice.markAsPristine();
    if (!this.roundChoice.dirty) this.roundChoice.setValue(current);
    if (!this.minutes.dirty) this.minutes.setValue(String(state.roundMinutes));
  }
}
