import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from "@angular/core";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { ActivatedRoute, RouterLink } from "@angular/router";
import { message } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import { AiRoomResults, AiRoomTeamStatus, QuizSection } from "../../../../core/event/event-models";
import { formatTime } from "../../../../core/event/format";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiPageState } from "../../../../shared/ui/ui-page-state/ui-page-state";
import { UiStatusChip, UiStatusChipVariant } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { BoardLoader } from "../board-loader";
import { SECTION_LABELS } from "./quiz-editor-view";

/** Answers do not change the event revision, so this page asks for fresh results on its own while it is open. */
const REFRESH_MS = 5000;
const SECTIONS: readonly QuizSection[] = ["EASY", "MEDIUM", "ADVANCED", "LANDMARK", "RECENT"];
const STATUS_LABELS: Record<AiRoomTeamStatus, string> = {
  IN_PROGRESS: "In progress",
  PASSED: "Passed",
  NOT_PASSED: "Not passed",
};
const STATUS_VARIANTS: Record<AiRoomTeamStatus, UiStatusChipVariant> = {
  IN_PROGRESS: "neutral",
  PASSED: "success",
  NOT_PASSED: "danger",
};

/**
 * /event/room/:roomId/quiz/results: the AI room's live scores for its manager (or ADMIN): per team the MCQ score
 * per section, each repair, the total out of the room's points, its status against the pass score and whether it
 * holds the room's clue, and per question how many teams got it right. Ranking inside the room only; the event's
 * final ranking does not use it.
 */
@Component({
  imports: [RouterLink, UiAlert, UiButton, UiCard, UiPageState, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./quiz-results.page.css",
  templateUrl: "./quiz-results.page.html",
})
export class EventQuizResultsPage {
  private readonly eventApi = inject(EventApi);
  protected readonly roomId = signal(0);
  protected readonly loader = new BoardLoader<AiRoomResults>(() => this.eventApi.quizResults(this.roomId()));
  protected readonly results = this.loader.data;
  protected readonly sections = SECTIONS;
  protected readonly sectionLabels = SECTION_LABELS;
  protected readonly statusLabels = STATUS_LABELS;
  protected readonly statusVariants = STATUS_VARIANTS;
  protected readonly formatTime = formatTime;
  protected readonly exportError = signal<string | null>(null);
  protected readonly updated = computed(() => formatTime(this.results()?.generatedAt ?? null));

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
        void this.loader.load();
      });
    const timer = setInterval(() => {
      if (this.roomId() > 0 && document.visibilityState === "visible") void this.loader.load();
    }, REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected reload(): void {
    void this.loader.load();
  }

  protected score(value: number | null): string {
    return value === null ? "—" : value.toFixed(2);
  }

  protected async exportCsv(): Promise<void> {
    this.exportError.set(null);
    try {
      await this.eventApi.downloadQuizResultsCsv(this.roomId());
    } catch (e) {
      this.exportError.set(message(e));
    }
  }
}
