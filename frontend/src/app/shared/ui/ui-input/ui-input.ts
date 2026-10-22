import {
  ChangeDetectionStrategy,
  Component,
  computed,
  forwardRef,
  input,
  signal,
} from "@angular/core";
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from "@angular/forms";

/**
 * `'color'` renders the native swatch picker; its value is always a lower-case
 * `#rrggbb`. Pair it with a text field when users need to type an exact hex.
 */
export type UiInputType =
  | "text"
  | "email"
  | "password"
  | "number"
  | "tel"
  | "search"
  | "url"
  | "date"
  | "color";

let nextUiInputId = 0;

/**
 * Presentational text input. Implements ControlValueAccessor so it drops into
 * Reactive Forms via `formControlName` — the component itself has no knowledge
 * of forms beyond that contract.
 *
 * Optionally project trailing content (a password-visibility toggle, a unit
 * label, a clear button) with the `ui-input-suffix` attribute; the slot is
 * collapsed to nothing when unused.
 */
@Component({
  selector: "ui-input",
  templateUrl: "./ui-input.html",
  styleUrl: "./ui-input.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => UiInput),
      multi: true,
    },
  ],
})
export class UiInput implements ControlValueAccessor {
  readonly label = input.required<string>();
  readonly type = input<UiInputType>("text");
  readonly placeholder = input("");
  readonly hint = input<string | null>(null);
  readonly error = input<string | null>(null);
  readonly required = input(false);
  readonly autocomplete = input("off");
  readonly disabled = input(false);
  /** Shrinks the control's height/padding for tight layouts (e.g. a long form that must fit one screen). */
  readonly compact = input(false);
  /** Keeps `label` for assistive tech but hides it visually — a toolbar/nav search box, say, where the placeholder already conveys purpose. */
  readonly labelHidden = input(false);

  protected readonly inputId = `ui-input-${nextUiInputId++}`;
  protected readonly value = signal("");
  private readonly disabledByForm = signal(false);

  protected readonly isDisabled = computed(
    () => this.disabled() || this.disabledByForm(),
  );

  protected readonly describedById = computed(() => {
    if (this.error()) {
      return `${this.inputId}-error`;
    }
    if (this.hint()) {
      return `${this.inputId}-hint`;
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

  protected handleInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.value.set(value);
    this.onChange(value);
  }

  protected handleBlur(): void {
    this.onTouched();
  }
}
