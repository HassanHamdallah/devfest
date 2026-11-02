import {
  AbstractControl,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from "@angular/forms";

/** Same bounds as the backend (12..72; 72 is the bcrypt input limit). */
export const passwordValidators: ValidatorFn[] = [
  Validators.required,
  Validators.minLength(12),
  Validators.maxLength(72),
];

/** Required and not only whitespace (the backend uses @NotBlank). */
export function notBlank(control: AbstractControl): ValidationErrors | null {
  const value: unknown = control.value;
  return typeof value === "string" && value.trim() ? null : { blank: true };
}

/** Message for a touched, invalid control; null while untouched or valid. */
export function fieldError(
  control: AbstractControl,
  label: string,
): string | null {
  if (!control.touched || control.valid) return null;
  const errors = control.errors ?? {};
  if (errors["required"] || errors["blank"]) return `${label} is required.`;
  if (errors["email"]) return "Enter a valid email address.";
  if (errors["minlength"])
    return `${label} needs at least ${lengthLimit(errors["minlength"])} characters.`;
  if (errors["maxlength"])
    return `${label} is too long (max ${lengthLimit(errors["maxlength"])} characters).`;
  return `${label} is not valid.`;
}

function lengthLimit(error: unknown): number {
  return (error as { requiredLength: number }).requiredLength;
}
