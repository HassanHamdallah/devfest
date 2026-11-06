import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  signal,
} from "@angular/core";
import { HttpErrorResponse } from "@angular/common/http";
import { message } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import { AiRoomActivity } from "../../../../core/event/event-models";
import { newIdempotencyKey } from "../../../../core/event/pending-request";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { firstUnanswered, nextUnanswered } from "./quiz-view";

/**
 * The multiple-choice part: one question at a time with its section and pacing hint, a navigator of every
 * question (jump to any), "Save answer" (locks it), and "Finish MCQs" (asks first; cannot be undone). The team
 * only ever learns "Response saved".
 */
@Component({
  selector: "event-quiz-mcq",
  imports: [UiAlert, UiButton, UiConfirmDialog, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./quiz-mcq.css",
  templateUrl: "./quiz-mcq.html",
})
export class EventQuizMcq {
  private readonly eventApi = inject(EventApi);

  readonly activity = input.required<AiRoomActivity>();
  readonly secondsLeft = input.required<number>();
  readonly secondsLeftLabel = input.required<string>();
  /** Paused, closed room or disqualified team: nothing can be saved. */
  readonly readOnly = input.required<boolean>();
  /** An answer was saved: the parent fetches the activity again. */
  readonly saved = output<void>();
  /** "Finish MCQs" answered with the new activity. */
  readonly finished = output<AiRoomActivity>();

  private readonly questionKey = computed(() => this.activity().questions.map((q) => q.id).join(","));
  /** The question on screen; stays put across refreshes and starts at the first unanswered one. */
  protected readonly current = linkedSignal<string, number>({
    source: this.questionKey,
    computation: (_, previous) => previous?.value ?? firstUnanswered(this.activity()),
  });
  protected readonly question = computed(() => this.activity().questions[this.current()] ?? null);
  protected readonly section = computed(() => {
    const question = this.question();
    return this.activity().sections.find((s) => s.section === question?.section) ?? null;
  });
  /** The option the team picked for the question on screen; the saved one once answered. */
  protected readonly choice = linkedSignal<number | null, number | null>({
    source: () => this.question()?.id ?? null,
    computation: () => this.question()?.selectedOptionId ?? null,
  });
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  /** The question whose answer was just saved, for the "Response saved" line. */
  protected readonly justSaved = signal<number | null>(null);
  protected readonly confirmFinish = signal(false);
  protected readonly finishing = signal(false);
  protected readonly unanswered = computed(() => this.activity().questionCount - this.activity().answeredCount);
  /** One key per question and chosen option, so a retry after a lost response replays the same answer. */
  private readonly keys = new Map<number, { optionId: number; key: string }>();

  protected go(index: number): void {
    this.current.set(index);
    this.error.set(null);
    this.justSaved.set(null);
  }

  protected step(delta: number): void {
    const count = this.activity().questions.length;
    this.go((this.current() + delta + count) % count);
  }

  protected async save(): Promise<void> {
    const question = this.question();
    const optionId = this.choice();
    if (!question || optionId === null || this.sending() || this.readOnly()) return;
    const stored = this.keys.get(question.id);
    const key = stored?.optionId === optionId ? stored.key : newIdempotencyKey();
    this.keys.set(question.id, { optionId, key });
    this.sending.set(true);
    this.error.set(null);
    try {
      await this.eventApi.saveQuizAnswer(this.activity().roomId, question.id, optionId, key);
      this.keys.delete(question.id);
      this.justSaved.set(question.id);
      const next = nextUnanswered(this.withAnswered(question.id), this.current());
      if (next !== null) this.current.set(next);
      this.saved.emit();
    } catch (e) {
      this.error.set(failure(e));
      // A refused answer (already answered, part closed) shows the server's current state.
      if (e instanceof HttpErrorResponse && e.status >= 400 && e.status < 500) this.saved.emit();
    } finally {
      this.sending.set(false);
    }
  }

  protected async finish(): Promise<void> {
    if (this.finishing()) return;
    this.finishing.set(true);
    this.error.set(null);
    try {
      this.finished.emit(await this.eventApi.finishQuizMcq(this.activity().roomId));
      this.confirmFinish.set(false);
    } catch (e) {
      this.error.set(message(e));
      this.confirmFinish.set(false);
    } finally {
      this.finishing.set(false);
    }
  }

  /** The activity as if `questionId` were answered, to find the next open question before the refresh. */
  private withAnswered(questionId: number): AiRoomActivity {
    const activity = this.activity();
    return {
      ...activity,
      questions: activity.questions.map((q) => (q.id === questionId ? { ...q, selectedOptionId: q.selectedOptionId ?? -1 } : q)),
    };
  }
}

function failure(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 0) {
    return "The server did not answer. Your choice is kept on this screen; press Save answer again.";
  }
  return message(e);
}
