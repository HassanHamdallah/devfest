import { AbstractControl, FormGroup } from "@angular/forms";

/**
 * Applies fresh server values to a content form unless the organizer has unsaved edits, so a
 * revision-driven reload never wipes typing. A dirty form keeps its old base version and the
 * server answers a stale save with 409. Once content is read-only, server values always win.
 * Returns true when the values were applied.
 */
export function syncEditableForm<C extends Record<string, AbstractControl>>(
  form: FormGroup<C>,
  readOnly: boolean,
  values: FormGroup<C>["value"],
): boolean {
  const apply = readOnly || !form.dirty;
  if (apply) form.reset(values);
  if (readOnly) form.disable({ emitEvent: false });
  else form.enable({ emitEvent: false });
  return apply;
}
