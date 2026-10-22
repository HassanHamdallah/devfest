import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  forwardRef,
  input,
  model,
  output,
  signal,
  viewChild,
} from "@angular/core";
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from "@angular/forms";

let nextUiTextareaId = 0;

/** Keys that move the caret without changing the value -- the ones `caretChange` also fires on. */
const CARET_MOVING_KEYS = new Set([
  "ArrowLeft",
  "ArrowRight",
  "ArrowUp",
  "ArrowDown",
  "Home",
  "End",
  "PageUp",
  "PageDown",
]);

export interface UiTextareaCaretState {
  value: string;
  caret: number;
}

/**
 * Multi-line sibling of `ui-input`: same API, same ControlValueAccessor contract,
 * so it drops into Reactive Forms via `formControlName` and knows nothing about
 * forms beyond that.
 *
 * Also exposes generic caret/keyboard hooks (`caretChange`, `keydown`,
 * `replaceRange`, and the `aria*` passthrough inputs) so a host directive —
 * `appMentionAutocomplete` is the only consumer today — can build an @mention
 * popup on top without this component knowing anything about mentions itself.
 * It stays presentational; nothing here is mention-specific.
 */
@Component({
  selector: "ui-textarea",
  templateUrl: "./ui-textarea.html",
  styleUrl: "./ui-textarea.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => UiTextarea),
      multi: true,
    },
  ],
})
export class UiTextarea implements ControlValueAccessor {
  readonly label = input.required<string>();
  readonly placeholder = input("");
  readonly hint = input<string | null>(null);
  readonly error = input<string | null>(null);
  readonly required = input(false);
  readonly disabled = input(false);
  readonly rows = input(6);

  /**
   * Passed straight through to the native textarea. `model()`, not `input()`,
   * on purpose: a sibling directive on this same host element (`appMentionAutocomplete`)
   * injects this component directly and drives these by calling `.set(...)`
   * on them as its popup opens/closes/moves, without the template that placed
   * `<ui-textarea>` needing to bind anything itself.
   */
  readonly ariaAutocomplete = model<string | null>(null);
  readonly ariaExpanded = model<boolean | null>(null);
  readonly ariaControls = model<string | null>(null);
  readonly ariaActiveDescendant = model<string | null>(null);

  /** Emits on input, click, and a caret-moving keyup — never on a plain character keyup, which `input` already covers. */
  readonly caretChange = output<UiTextareaCaretState>();
  /** Re-emits the native keydown event so a host can inspect it and `preventDefault()` (e.g. to stop Enter/Tab from committing while a popup is open). */
  readonly keydownEvent = output<KeyboardEvent>();

  private readonly textareaRef =
    viewChild.required<ElementRef<HTMLTextAreaElement>>("textareaEl");

  protected readonly textareaId = `ui-textarea-${nextUiTextareaId++}`;
  protected readonly value = signal("");
  private readonly disabledByForm = signal(false);

  protected readonly isDisabled = computed(
    () => this.disabled() || this.disabledByForm(),
  );

  protected readonly describedById = computed(() => {
    if (this.error()) {
      return `${this.textareaId}-error`;
    }
    if (this.hint()) {
      return `${this.textareaId}-hint`;
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

  /**
   * Replaces `[start, end)` with `text`, notifies the form through the same
   * `onChange` callback a normal keystroke uses, and places the caret right
   * after the inserted text. Sets the native element's value directly (rather
   * than waiting on the `value()` signal to flow back through the template
   * binding) so the caret can be positioned in the same tick, with no flicker.
   */
  replaceRange(start: number, end: number, text: string): void {
    const current = this.value();
    const nextValue = current.slice(0, start) + text + current.slice(end);
    const caret = start + text.length;

    this.value.set(nextValue);
    this.onChange(nextValue);

    const element = this.textareaRef().nativeElement;
    element.value = nextValue;
    element.setSelectionRange(caret, caret);
    element.focus();
  }

  /** The native textarea's own bounding rect (not the wrapping label/hint) — for a host positioning a popup directly below it. */
  getTextareaBoundingRect(): DOMRect {
    return this.textareaRef().nativeElement.getBoundingClientRect();
  }

  protected handleInput(event: Event): void {
    const target = event.target as HTMLTextAreaElement;
    this.value.set(target.value);
    this.onChange(target.value);
    this.emitCaret(target);
  }

  protected handleBlur(): void {
    this.onTouched();
  }

  protected handleClick(event: MouseEvent): void {
    this.emitCaret(event.target as HTMLTextAreaElement);
  }

  protected handleKeyup(event: KeyboardEvent): void {
    if (CARET_MOVING_KEYS.has(event.key)) {
      this.emitCaret(event.target as HTMLTextAreaElement);
    }
  }

  protected handleKeydown(event: KeyboardEvent): void {
    this.keydownEvent.emit(event);
  }

  private emitCaret(target: HTMLTextAreaElement): void {
    this.caretChange.emit({
      value: target.value,
      caret: target.selectionStart ?? target.value.length,
    });
  }
}
