import { ChangeDetectionStrategy, Component, input, linkedSignal, output, signal } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { QuizEditorQuestion, QuizQuestionRequest, QuizSection } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { UiSelect } from "../../../../shared/ui/ui-select/ui-select";
import { UiTextarea } from "../../../../shared/ui/ui-textarea/ui-textarea";
import { SECTION_OPTIONS, questionProblem } from "./quiz-editor-view";

/** Add or edit one question: section, prompt, 2–6 options with exactly one correct, points and explanation. */
@Component({
  selector: "event-quiz-question-form",
  imports: [FormsModule, UiAlert, UiButton, UiInput, UiSelect, UiTextarea],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./quiz-forms.css",
  template: `
    <form class="quiz-form" (ngSubmit)="submit()" [attr.aria-label]="question() ? 'Edit question' : 'New question'">
      <h3>{{ question() ? "Edit question" : "New question" }}</h3>
      <ui-select label="Section" name="section" [options]="sections" [ngModel]="section()" (ngModelChange)="section.set($event)" />
      <ui-textarea label="Question" name="prompt" [rows]="3" [ngModel]="prompt()" (ngModelChange)="prompt.set($event)" />
      <fieldset class="quiz-form__options">
        <legend>Options (mark the correct one)</legend>
        @for (option of options(); track $index; let i = $index) {
          <div class="quiz-form__option">
            <input
              type="radio"
              name="correct"
              [checked]="correct() === i"
              [attr.aria-label]="'Option ' + (i + 1) + ' is correct'"
              (change)="correct.set(i)"
            />
            <ui-input
              [label]="'Option ' + (i + 1)"
              [name]="'option' + i"
              [compact]="true"
              [ngModel]="option"
              (ngModelChange)="setOption(i, $event)"
            />
            <ui-button variant="ghost" size="sm" [disabled]="options().length <= 2" (click)="removeOption(i)">Remove</ui-button>
          </div>
        }
        <ui-button variant="secondary" size="sm" [disabled]="options().length >= 6" (click)="addOption()">Add option</ui-button>
      </fieldset>
      <ui-input label="Points (1–10)" name="points" type="number" [ngModel]="points()" (ngModelChange)="points.set($event)" />
      <ui-textarea
        label="Explanation (organizers only)"
        name="explanation"
        [rows]="2"
        [ngModel]="explanation()"
        (ngModelChange)="explanation.set($event)"
      />
      @if (problem(); as text) {
        <ui-alert variant="error">{{ text }}</ui-alert>
      }
      <div class="event-inline">
        <ui-button type="submit" [loading]="busy()">Save question</ui-button>
        <ui-button variant="ghost" (click)="cancelled.emit()">Cancel</ui-button>
      </div>
    </form>
  `,
})
export class EventQuizQuestionForm {
  /** Null: a new question. */
  readonly question = input<QuizEditorQuestion | null>(null);
  readonly busy = input(false);
  readonly saved = output<QuizQuestionRequest>();
  readonly cancelled = output<void>();

  protected readonly sections = SECTION_OPTIONS;
  protected readonly section = linkedSignal<QuizSection>(() => this.question()?.section ?? "EASY");
  protected readonly prompt = linkedSignal(() => this.question()?.prompt ?? "");
  protected readonly options = linkedSignal(() => [...(this.question()?.options ?? ["", "", "", ""])]);
  protected readonly correct = linkedSignal(() => this.question()?.correctOption ?? 0);
  protected readonly points = linkedSignal(() => String(this.question()?.points ?? 1));
  protected readonly explanation = linkedSignal(() => this.question()?.explanation ?? "");
  protected readonly problem = signal<string | null>(null);

  protected setOption(index: number, text: string): void {
    this.options.update((options) => options.map((option, i) => (i === index ? text : option)));
  }

  protected addOption(): void {
    this.options.update((options) => [...options, ""]);
  }

  protected removeOption(index: number): void {
    this.options.update((options) => options.filter((_, i) => i !== index));
    if (this.correct() >= index && this.correct() > 0) this.correct.update((c) => c - 1);
  }

  protected submit(): void {
    const body: QuizQuestionRequest = {
      section: this.section(),
      prompt: this.prompt().trim(),
      options: this.options().map((o) => o.trim()),
      correctOption: this.correct(),
      points: Number(this.points()),
      explanation: this.explanation().trim(),
    };
    const problem = questionProblem(body);
    this.problem.set(problem);
    if (!problem) this.saved.emit(body);
  }
}
