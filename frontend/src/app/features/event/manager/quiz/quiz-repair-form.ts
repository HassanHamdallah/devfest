import { ChangeDetectionStrategy, Component, input, linkedSignal, output, signal } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { QuizEditorRepair, QuizRepairRequest } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { UiTextarea } from "../../../../shared/ui/ui-textarea/ui-textarea";
import { repairProblem } from "./quiz-editor-view";

/** Add or edit one repair: title, instructions, points and 4–6 code snippets in their correct order. */
@Component({
  selector: "event-quiz-repair-form",
  imports: [FormsModule, UiAlert, UiButton, UiInput, UiTextarea],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./quiz-forms.css",
  template: `
    <form class="quiz-form" (ngSubmit)="submit()" [attr.aria-label]="repair() ? 'Edit repair' : 'New repair'">
      <h3>{{ repair() ? "Edit repair" : "New repair" }}</h3>
      <ui-input label="Title" name="title" [ngModel]="title()" (ngModelChange)="title.set($event)" />
      <ui-textarea label="Instructions" name="instructions" [rows]="2" [ngModel]="instructions()" (ngModelChange)="instructions.set($event)" />
      <ui-input label="Points" name="points" type="number" [ngModel]="points()" (ngModelChange)="points.set($event)" />
      <fieldset class="quiz-form__options">
        <legend>Snippets in their correct order (teams get them shuffled)</legend>
        @for (snippet of snippets(); track $index; let i = $index) {
          <div class="quiz-form__option quiz-form__option--code">
            <ui-textarea
              [label]="'Line ' + (i + 1)"
              [name]="'snippet' + i"
              [rows]="2"
              [ngModel]="snippet"
              (ngModelChange)="setSnippet(i, $event)"
            />
            <ui-button variant="ghost" size="sm" [disabled]="snippets().length <= 4" (click)="removeSnippet(i)">Remove</ui-button>
          </div>
        }
        <ui-button variant="secondary" size="sm" [disabled]="snippets().length >= 6" (click)="addSnippet()">Add snippet</ui-button>
      </fieldset>
      @if (problem(); as text) {
        <ui-alert variant="error">{{ text }}</ui-alert>
      }
      <div class="event-inline">
        <ui-button type="submit" [loading]="busy()">Save repair</ui-button>
        <ui-button variant="ghost" (click)="cancelled.emit()">Cancel</ui-button>
      </div>
    </form>
  `,
})
export class EventQuizRepairForm {
  /** Null: a new repair. */
  readonly repair = input<QuizEditorRepair | null>(null);
  readonly busy = input(false);
  readonly saved = output<QuizRepairRequest>();
  readonly cancelled = output<void>();

  protected readonly title = linkedSignal(() => this.repair()?.title ?? "");
  protected readonly instructions = linkedSignal(() => this.repair()?.instructions ?? "Put the lines in the right order.");
  protected readonly points = linkedSignal(() => String(this.repair()?.points ?? 10));
  protected readonly snippets = linkedSignal(() => [...(this.repair()?.snippets ?? ["", "", "", ""])]);
  protected readonly problem = signal<string | null>(null);

  protected setSnippet(index: number, code: string): void {
    this.snippets.update((snippets) => snippets.map((snippet, i) => (i === index ? code : snippet)));
  }

  protected addSnippet(): void {
    this.snippets.update((snippets) => [...snippets, ""]);
  }

  protected removeSnippet(index: number): void {
    this.snippets.update((snippets) => snippets.filter((_, i) => i !== index));
  }

  protected submit(): void {
    const body: QuizRepairRequest = {
      title: this.title().trim(),
      instructions: this.instructions().trim(),
      points: Number(this.points()),
      // Code keeps its indentation; only trailing blank lines go.
      snippets: this.snippets().map((s) => s.replace(/\s+$/, "")),
    };
    const problem = repairProblem(body);
    this.problem.set(problem);
    if (!problem) this.saved.emit(body);
  }
}
