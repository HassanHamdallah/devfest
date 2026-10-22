import {
  ChangeDetectionStrategy,
  Component,
  computed,
  forwardRef,
  input,
  signal,
} from "@angular/core";
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from "@angular/forms";

export interface UiSelectOption {
  value: string;
  label: string;
}

let nextUiSelectId = 0;

/**
 * Presentational select. Implements ControlValueAccessor so it drops into
 * Reactive Forms via `formControlName` — same contract as `ui-input`, plus an
 * `options` input. A plain styled native `<select>` under the hood, not a
 * custom popup listbox.
 */
@Component({
  selector: "ui-select",
  templateUrl: "./ui-select.html",
  styleUrl: "./ui-select.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => UiSelect),
      multi: true,
    },
  ],
})
export class UiSelect implements ControlValueAccessor {
  readonly label = input.required<string>();
  readonly options = input.required<readonly UiSelectOption[]>();
  readonly placeholder = input("");
  readonly hint = input<string | null>(null);
  readonly error = input<string | null>(null);
  readonly required = input(false);
  readonly disabled = input(false);
  /** Shrinks the control's height/padding for tight layouts (e.g. a long form that must fit one screen). */
  readonly compact = input(false);

  protected readonly selectId = `ui-select-${nextUiSelectId++}`;
  protected readonly value = signal("");
  private readonly disabledByForm = signal(false);

  protected readonly isDisabled = computed(
    () => this.disabled() || this.disabledByForm(),
  );

  protected readonly describedById = computed(() => {
    if (this.error()) {
      return `${this.selectId}-error`;
    }
    if (this.hint()) {
      return `${this.selectId}-hint`;
    }
    return null;
  });

  private onChange: (value: string) => void = () => {};
  private onTouched: () => void = () => {};

  writeValue(value: string): void {
    this.value.set(value ?? "");
  }

  registerOnChange(fn: (value: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabledByForm.set(isDisabled);
  }

  protected handleChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.value.set(value);
    this.onChange(value);
  }

  protected handleBlur(): void {
    this.onTouched();
  }
}
