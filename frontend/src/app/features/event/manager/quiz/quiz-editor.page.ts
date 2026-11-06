import { ChangeDetectionStrategy, Component, computed, inject, signal } from "@angular/core";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { FormsModule } from "@angular/forms";
import { ActivatedRoute, RouterLink } from "@angular/router";
import { message } from "../../../../core/api";
import { EventApi, saveFile } from "../../../../core/event/event-api";
import {
  QuizEditorQuestion,
  QuizEditorRepair,
  QuizEditorView,
  QuizQuestionRequest,
  QuizRepairRequest,
} from "../../../../core/event/event-models";
import { reloadOnRevisionChange } from "../../../../core/event/revision-sync";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { UiPageState } from "../../../../shared/ui/ui-page-state/ui-page-state";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { BoardLoader } from "../board-loader";
import { SECTION_LABELS } from "./quiz-editor-view";
import { EventQuizQuestionForm } from "./quiz-question-form";
import { EventQuizRepairForm } from "./quiz-repair-form";

/** What the form below the lists edits; id null = a new item. */
type Draft = { kind: "question"; item: QuizEditorQuestion | null } | { kind: "repair"; item: QuizEditorRepair | null };

/**
 * /event/room/:roomId/quiz: the AI room's questions and repairs for the room's manager (or ADMIN), with totals per
 * section, planning warnings, import/export of the whole content as one JSON file, and the MCQ minutes.
 */
@Component({
  imports: [
    EventQuizQuestionForm,
    EventQuizRepairForm,
    FormsModule,
    RouterLink,
    UiAlert,
    UiButton,
    UiCard,
    UiConfirmDialog,
    UiInput,
    UiPageState,
    UiStatusChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./quiz-editor.page.css",
  templateUrl: "./quiz-editor.page.html",
})
export class EventQuizEditorPage {
  private readonly eventApi = inject(EventApi);
  protected readonly roomId = signal(0);
  protected readonly loader = new BoardLoader<QuizEditorView>(() => this.eventApi.quizEditor(this.roomId()));
  protected readonly view = this.loader.data;
  protected readonly sectionLabels = SECTION_LABELS;

  protected readonly draft = signal<Draft | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly deleting = signal<{ kind: "question" | "repair"; id: number; label: string } | null>(null);
  /** A parsed import file waiting for "Replace". */
  protected readonly pendingImport = signal<{ name: string; document: unknown; questions: number; repairs: number } | null>(null);
  protected readonly mcqMinutes = signal("");
  protected readonly passScore = signal("");

  protected readonly groups = computed(() => {
    const view = this.view();
    if (!view) return [];
    return view.sections.map((section) => ({
      ...section,
      items: view.questions.filter((q) => q.section === section.section),
    }));
  });

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed())
      .subscribe((params) => {
        const id = Number(params.get("roomId"));
        if (!Number.isInteger(id) || id <= 0) {
          this.loader.fail("Room not found.");
          return;
        }
        this.roomId.set(id);
        this.loader.reset();
        void this.reload();
      });
    reloadOnRevisionChange(() => void this.reload());
  }

  protected async reload(): Promise<void> {
    if (this.roomId() === 0) return;
    await this.loader.load();
    const view = this.view();
    if (view && !this.busy()) this.showSettings(view);
  }

  protected edit(draft: Draft): void {
    this.error.set(null);
    this.notice.set(null);
    this.draft.set(draft);
  }

  protected saveQuestion(body: QuizQuestionRequest): void {
    const draft = this.draft();
    const id = draft?.kind === "question" ? draft.item?.id : undefined;
    void this.run(
      () =>
        id === undefined
          ? this.eventApi.createQuizQuestion(this.roomId(), body)
          : this.eventApi.updateQuizQuestion(this.roomId(), id, body),
      "Question saved.",
    );
  }

  protected saveRepair(body: QuizRepairRequest): void {
    const draft = this.draft();
    const id = draft?.kind === "repair" ? draft.item?.id : undefined;
    void this.run(
      () =>
        id === undefined
          ? this.eventApi.createQuizRepair(this.roomId(), body)
          : this.eventApi.updateQuizRepair(this.roomId(), id, body),
      "Repair saved.",
    );
  }

  protected async confirmDelete(): Promise<void> {
    const target = this.deleting();
    if (!target) return;
    await this.run(
      () =>
        target.kind === "question"
          ? this.eventApi.deleteQuizQuestion(this.roomId(), target.id)
          : this.eventApi.deleteQuizRepair(this.roomId(), target.id),
      `Deleted ${target.label}.`,
    );
    this.deleting.set(null);
  }

  /** A locked pass score is not sent (null keeps it). */
  protected saveSettings(): void {
    const minutes = Number(this.mcqMinutes());
    if (!Number.isInteger(minutes) || minutes < 1 || minutes > 180) {
      this.error.set("Multiple-choice minutes must be a whole number from 1 to 180.");
      return;
    }
    const locked = this.view()?.passScoreLocked ?? true;
    const passScore = Number(this.passScore());
    if (!locked && (String(this.passScore()).trim() === "" || !Number.isInteger(passScore) || passScore < 0 || passScore > 100)) {
      this.error.set("The pass score must be a whole number from 0 to 100.");
      return;
    }
    void this.run(() => this.eventApi.saveQuizSettings(this.roomId(), minutes, locked ? null : passScore), "Quiz settings saved.");
  }

  /** Reads the chosen JSON file in the browser; nothing is sent before "Replace". */
  protected async chooseFile(event: Event): Promise<void> {
    const picker = event.target as HTMLInputElement;
    const file = picker.files?.[0];
    picker.value = "";
    if (!file) return;
    this.error.set(null);
    this.notice.set(null);
    try {
      const document: unknown = JSON.parse(await file.text());
      const parsed = document as { questions?: unknown[]; repairs?: unknown[] };
      this.pendingImport.set({
        name: file.name,
        document,
        questions: Array.isArray(parsed?.questions) ? parsed.questions.length : 0,
        repairs: Array.isArray(parsed?.repairs) ? parsed.repairs.length : 0,
      });
    } catch {
      this.error.set(`${file.name} is not a valid JSON file.`);
    }
  }

  protected async confirmImport(): Promise<void> {
    const pending = this.pendingImport();
    if (!pending) return;
    await this.run(
      () => this.eventApi.importQuiz(this.roomId(), pending.document),
      `Imported ${pending.questions} questions and ${pending.repairs} repairs from ${pending.name}.`,
    );
    this.pendingImport.set(null);
  }

  protected async exportFile(): Promise<void> {
    try {
      const document = await this.eventApi.exportQuiz(this.roomId());
      const name = (this.view()?.roomName ?? "room").toLowerCase().replace(/[^a-z0-9]+/g, "-");
      saveFile(JSON.stringify(document, null, 2), "application/json", `${name}-quiz.json`);
    } catch (e) {
      this.error.set(message(e));
    }
  }

  private showSettings(view: QuizEditorView): void {
    this.mcqMinutes.set(String(view.mcqMinutes));
    this.passScore.set(String(view.passScore));
  }

  private async run(work: () => Promise<QuizEditorView>, done: string): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      const view = await work();
      this.loader.data.set(view);
      this.showSettings(view);
      this.draft.set(null);
      this.notice.set(done);
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.busy.set(false);
    }
  }
}
