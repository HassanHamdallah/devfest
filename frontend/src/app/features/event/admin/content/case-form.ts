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
import { AdminContent } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiSelect } from "../../../../shared/ui/ui-select/ui-select";
import { UiTextarea } from "../../../../shared/ui/ui-textarea/ui-textarea";
import { ActionState } from "../shared/action-state";
import { ActionStatus } from "../shared/action-status";
import { fieldError } from "../shared/form-rules";
import { syncEditableForm } from "./editable-form";
import { NO_SUSPECT, correctSuspectOptions } from "./content-view";

/** The encrypted message teams decrypt and the correct suspect (PUT /admin/content, optimistic version). */
@Component({
  selector: "app-case-form",
  imports: [
    ActionStatus,
    ReactiveFormsModule,
    UiButton,
    UiCard,
    UiSelect,
    UiTextarea,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<ui-card>
    <h2 ui-card-header>Encrypted message and solution</h2>
    <form
      aria-label="Encrypted message and solution"
      [formGroup]="form"
      (ngSubmit)="save()"
    >
      <ui-textarea
        label="Encrypted message"
        [rows]="5"
        hint="Shown to teams that collected all four clues."
        [error]="messageError()"
        formControlName="encryptedMessage"
      />
      <ui-select
        label="Correct suspect"
        [options]="suspectOptions()"
        formControlName="correctSuspectId"
      />
      @if (!content().readOnly) {
        <ui-button type="submit" [loading]="state.busy()"
          >Save message and solution</ui-button
        >
      }
      <app-action-status
        [state]="state"
        [reloadable]="true"
        (reload)="discardEdits()"
      />
    </form>
  </ui-card>`,
  styles: `
    h2 {
      margin: 0;
      font-size: var(--font-size-lg);
    }
    form {
      display: grid;
      justify-items: start;
      gap: var(--space-3);
    }
    ui-textarea,
    ui-select,
    app-action-status {
      justify-self: stretch;
    }
  `,
})
export class CaseForm {
  private readonly eventApi = inject(EventApi);

  readonly content = input.required<AdminContent>();
  readonly changed = output<void>();

  protected readonly state = new ActionState();
  protected readonly form = new FormGroup({
    encryptedMessage: new FormControl("", {
      nonNullable: true,
      validators: [Validators.maxLength(4000)],
    }),
    correctSuspectId: new FormControl(NO_SUSPECT, { nonNullable: true }),
  });
  protected readonly suspectOptions = computed(() =>
    correctSuspectOptions(this.content()),
  );
  /** Version of the content the current form values are based on. */
  private baseVersion = 0;

  constructor() {
    effect(() => {
      const content = this.content();
      untracked(() => this.sync(content));
    });
  }

  protected messageError(): string | null {
    return fieldError(this.form.controls.encryptedMessage, "Encrypted message");
  }

  protected async save(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.content().readOnly) return;
    const { encryptedMessage, correctSuspectId } = this.form.getRawValue();
    const body = {
      encryptedMessage,
      correctSuspectId:
        correctSuspectId === NO_SUSPECT ? null : Number(correctSuspectId),
      version: this.baseVersion,
    };
    const saved = await this.state.run(
      () => this.eventApi.saveAdminContent(body),
      "Saved.",
    );
    if (!saved) return;
    this.form.markAsPristine();
    this.changed.emit();
  }

  /** "Reload" after a conflict: drop local edits and take the server's current content. */
  protected discardEdits(): void {
    this.form.markAsPristine();
    this.state.reset();
    this.changed.emit();
  }

  private sync(content: AdminContent): void {
    const synced = syncEditableForm(this.form, content.readOnly, {
      encryptedMessage: content.encryptedMessage,
      correctSuspectId:
        content.correctSuspectId === null
          ? NO_SUSPECT
          : String(content.correctSuspectId),
    });
    if (synced) this.baseVersion = content.version;
  }
}
