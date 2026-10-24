import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  untracked,
} from "@angular/core";
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from "@angular/forms";
import { EventApi } from "../../../../core/event/event-api";
import { SuspectView } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { ActionState } from "../shared/action-state";
import { ActionStatus } from "../shared/action-status";
import { fieldError, notBlank } from "../shared/form-rules";

/** Adds a suspect, or edits `suspect` in place of its list row. */
@Component({
  selector: "app-suspect-form",
  imports: [ActionStatus, ReactiveFormsModule, UiButton, UiInput],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<form
    [attr.aria-label]="formLabel()"
    [formGroup]="form"
    (ngSubmit)="save()"
  >
    <div class="suspect-form__fields">
      <ui-input label="Name" formControlName="name" [error]="error('name')" />
      <ui-input
        label="Description"
        formControlName="description"
        [error]="error('description')"
      />
    </div>
    <div class="event-inline">
      <ui-button type="submit" [loading]="state.busy()">{{
        submitLabel()
      }}</ui-button>
      @if (suspect()) {
        <ui-button variant="ghost" (click)="cancelled.emit()">Cancel</ui-button>
      }
    </div>
    <app-action-status [state]="state" />
  </form>`,
  styles: `
    form {
      display: grid;
      gap: var(--space-3);
    }
    .suspect-form__fields {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr));
      gap: var(--space-3);
    }
  `,
})
export class SuspectForm {
  private readonly eventApi = inject(EventApi);

  /** The suspect being edited; null adds a new one. */
  readonly suspect = input<SuspectView | null>(null);
  readonly saved = output<void>();
  readonly cancelled = output<void>();

  protected readonly state = new ActionState();
  protected readonly form = new FormGroup({
    name: new FormControl("", {
      nonNullable: true,
      validators: [notBlank, Validators.maxLength(80)],
    }),
    description: new FormControl("", {
      nonNullable: true,
      validators: [Validators.maxLength(300)],
    }),
  });
  protected readonly formLabel = computed(() => {
    const suspect = this.suspect();
    return suspect ? `Edit ${suspect.name}` : "Add suspect";
  });
  protected readonly submitLabel = computed(() =>
    this.suspect() ? "Save suspect" : "Add suspect",
  );

  /** Id of the suspect the form shows; undefined before the first sync. */
  private syncedId: number | null | undefined;

  constructor() {
    effect(() => {
      const suspect = this.suspect();
      untracked(() => this.sync(suspect));
    });
  }

  protected error(field: "name" | "description"): string | null {
    return fieldError(
      this.form.controls[field],
      field === "name" ? "Name" : "Description",
    );
  }

  protected async save(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    const { name, description } = this.form.getRawValue();
    const body = { name: name.trim(), description: description.trim() };
    const suspect = this.suspect();
    const ok = await this.state.run(
      () =>
        suspect
          ? this.eventApi.updateSuspect(suspect.id, body)
          : this.eventApi.createSuspect(body),
      suspect ? "Suspect saved." : "Suspect added.",
    );
    if (!ok) return;
    if (!suspect) this.form.reset({ name: "", description: "" });
    this.saved.emit();
  }

  /** A refresh of the same suspect keeps unsaved typing; another suspect always loads. */
  private sync(suspect: SuspectView | null): void {
    const id = suspect?.id ?? null;
    if (id === this.syncedId && this.form.dirty) return;
    this.syncedId = id;
    this.form.reset({
      name: suspect?.name ?? "",
      description: suspect?.description ?? "",
    });
  }
}
