import {
  ChangeDetectionStrategy,
  Component,
  effect,
  input,
  output,
} from "@angular/core";
import { FormControl, ReactiveFormsModule, Validators } from "@angular/forms";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { UiSelect } from "../../../../shared/ui/ui-select/ui-select";

const ADJUST_DIRECTIONS = [
  { value: "add", label: "Add time" },
  { value: "remove", label: "Remove time" },
] as const;

/** "Adjust…": add or remove 1–60 minutes; emits the signed number of seconds. */
@Component({
  selector: "app-round-adjust-dialog",
  imports: [ReactiveFormsModule, UiConfirmDialog, UiInput, UiSelect],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<ui-confirm-dialog
    [open]="open()"
    title="Adjust the round time"
    message="Removing more time than is left ends the round (time is up)."
    [busy]="busy()"
    (confirmed)="apply()"
    (cancelled)="cancelled.emit()"
  >
    <div class="round-adjust">
      <ui-select
        label="Change"
        [options]="directions"
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
    .round-adjust {
      display: grid;
      gap: var(--space-3);
    }
  `,
})
export class RoundAdjustDialog {
  readonly open = input(false);
  readonly busy = input(false);
  readonly adjust = output<number>();
  readonly cancelled = output<void>();

  protected readonly directions = ADJUST_DIRECTIONS;
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

  constructor() {
    // Every opening starts from "add 5 minutes".
    effect(() => {
      if (!this.open()) return;
      this.minutes.reset("5");
      this.direction.setValue("add");
    });
  }

  protected minutesError(): string | null {
    return this.minutes.touched && this.minutes.invalid
      ? "Enter whole minutes from 1 to 60."
      : null;
  }

  protected apply(): void {
    this.minutes.markAsTouched();
    if (this.minutes.invalid) return;
    const seconds = Number(this.minutes.value) * 60;
    this.adjust.emit(this.direction.value === "remove" ? -seconds : seconds);
  }
}
