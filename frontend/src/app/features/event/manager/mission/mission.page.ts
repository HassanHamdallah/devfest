import { HttpErrorResponse } from "@angular/common/http";
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, inject, signal, viewChild } from "@angular/core";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { ActivatedRoute, RouterLink } from "@angular/router";
import { message } from "../../../../core/api";
import { EventApi, saveFile } from "../../../../core/event/event-api";
import { AiRoomResults, AiRoomTeamStatus, MissionEditorView } from "../../../../core/event/event-models";
import { formatTime } from "../../../../core/event/format";
import { reloadOnRevisionChange } from "../../../../core/event/revision-sync";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiPageState } from "../../../../shared/ui/ui-page-state/ui-page-state";
import { UiStatusChip, UiStatusChipVariant } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { BoardLoader } from "../board-loader";

/** Stage points do not change the event revision, so the results ask for fresh numbers on their own while open. */
const REFRESH_MS = 5000;
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

/** An import file read in the browser, waiting for "Replace". Nothing is sent before that. */
interface PendingImport {
  name: string;
  text: string;
  stages: number | null;
}

/** The server's reasons for a refused file (400 with `problems`), or none. */
function problemsOf(error: unknown): string[] {
  const problems: unknown = error instanceof HttpErrorResponse ? error.error?.problems : null;
  return Array.isArray(problems) ? problems.filter((p): p is string => typeof p === "string") : [];
}

/**
 * /event/room/:roomId/mission: the AI room's SENTINEL page for its manager (or ADMIN): import and export of the
 * mission file, the pass score, the printable Field Guide, and the live results (per-stage points, total out of 100,
 * status, finish time, CSV). Teams never see any of it. The manual clue verdict stays on the room board.
 */
@Component({
  imports: [RouterLink, UiAlert, UiButton, UiCard, UiConfirmDialog, UiPageState, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./mission.page.css",
  templateUrl: "./mission.page.html",
})
export class EventMissionPage {
  private readonly eventApi = inject(EventApi);
  protected readonly roomId = signal(0);
  protected readonly loader = new BoardLoader<MissionEditorView>(() => this.eventApi.missionEditor(this.roomId()));
  protected readonly view = this.loader.data;
  protected readonly resultsLoader = new BoardLoader<AiRoomResults>(() => this.eventApi.missionResults(this.roomId()));
  protected readonly results = this.resultsLoader.data;
  protected readonly statusLabels = STATUS_LABELS;
  protected readonly statusVariants = STATUS_VARIANTS;
  protected readonly formatTime = formatTime;

  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>("importFile");
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly problems = signal<string[]>([]);
  protected readonly notice = signal<string | null>(null);
  protected readonly pending = signal<PendingImport | null>(null);
  protected readonly passScore = signal("");
  protected readonly updated = computed(() => formatTime(this.results()?.generatedAt ?? null));
  protected readonly canImport = computed(() => {
    const view = this.view();
    return !!view && view.activity === "AI_QUIZ" && view.editable && !view.workStarted;
  });
  protected readonly importBlocked = computed(() => {
    const view = this.view();
    if (!view) return null;
    if (view.activity !== "AI_QUIZ") return "The AI mission is not switched on for this room. The admin does that on the Content page.";
    if (!view.editable) return "Event content is read-only after the final opens.";
    if (view.workStarted) return "Teams have already worked in this room; the mission can no longer be replaced.";
    return null;
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
        this.resultsLoader.reset();
        void this.reload();
      });
    reloadOnRevisionChange(() => void this.reload());
    const timer = setInterval(() => {
      if (this.roomId() > 0 && document.visibilityState === "visible") void this.resultsLoader.load();
    }, REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected async reload(): Promise<void> {
    if (this.roomId() === 0) return;
    await Promise.all([this.loader.load(), this.resultsLoader.load()]);
    const view = this.view();
    if (view && !this.busy()) this.passScore.set(String(view.passScore));
  }

  protected score(value: number | null): string {
    return value === null ? "—" : String(Math.round(value * 100) / 100);
  }

  /** Reads the chosen file in the browser; the server checks it when the manager confirms. */
  protected async chooseFile(event: Event): Promise<void> {
    const picker = event.target as HTMLInputElement;
    const file = picker.files?.[0];
    picker.value = "";
    if (!file) return;
    this.clear();
    const text = await file.text();
    let stages: number | null = null;
    try {
      const missions = (JSON.parse(text) as { missions?: { stages?: unknown[] }[] }).missions;
      if (Array.isArray(missions)) stages = missions.reduce((sum, m) => sum + (Array.isArray(m?.stages) ? m.stages.length : 0), 0);
    } catch {
      // Not JSON: the server answers with "The file is not valid JSON." when it is sent.
    }
    this.pending.set({ name: file.name, text, stages });
  }

  protected async confirmImport(): Promise<void> {
    const pending = this.pending();
    if (!pending) return;
    this.busy.set(true);
    this.clear();
    try {
      const view = await this.eventApi.importMission(this.roomId(), pending.text);
      this.loader.data.set(view);
      this.passScore.set(String(view.passScore));
      this.notice.set(`Imported ${pending.name}: ${view.stages.length} stages.`);
      void this.resultsLoader.load();
    } catch (e) {
      this.problems.set(problemsOf(e));
      this.error.set(message(e));
    } finally {
      this.pending.set(null);
      this.busy.set(false);
    }
  }

  /** After a refused file: clears the reasons and opens the file chooser again. */
  protected tryAgain(): void {
    this.clear();
    this.fileInput()?.nativeElement.click();
  }

  protected async exportFile(): Promise<void> {
    this.clear();
    try {
      const document = await this.eventApi.exportMission(this.roomId());
      saveFile(JSON.stringify(document, null, 2), "application/json", "sentinel-mission.json");
    } catch (e) {
      this.error.set(message(e));
    }
  }

  protected async savePassScore(): Promise<void> {
    const value = Number(this.passScore());
    if (String(this.passScore()).trim() === "" || !Number.isInteger(value) || value < 0 || value > 100) {
      this.error.set("The pass score must be a whole number from 0 to 100.");
      return;
    }
    this.busy.set(true);
    this.clear();
    try {
      const view = await this.eventApi.saveMissionSettings(this.roomId(), value);
      this.loader.data.set(view);
      this.passScore.set(String(view.passScore));
      this.notice.set("Pass score saved.");
      void this.resultsLoader.load();
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.busy.set(false);
    }
  }

  protected async exportCsv(): Promise<void> {
    this.clear();
    try {
      await this.eventApi.downloadMissionResultsCsv(this.roomId());
    } catch (e) {
      this.error.set(message(e));
    }
  }

  private clear(): void {
    this.error.set(null);
    this.problems.set([]);
    this.notice.set(null);
  }
}
