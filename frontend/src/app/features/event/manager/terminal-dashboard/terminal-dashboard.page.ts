import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from "@angular/core";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { FormsModule } from "@angular/forms";
import { ActivatedRoute } from "@angular/router";
import { message } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import {
  TerminalAnswerKeys,
  TerminalDashboard,
  TerminalDashboardStatus,
  TerminalDashboardTeam,
  TerminalItemScore,
  TerminalScoreItem,
} from "../../../../core/event/event-models";
import { formatTime } from "../../../../core/event/format";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiPageState } from "../../../../shared/ui/ui-page-state/ui-page-state";
import { UiStatusChip, UiStatusChipVariant } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { UiTextarea } from "../../../../shared/ui/ui-textarea/ui-textarea";
import { EventLinuxTerminal } from "../../terminal/linux-terminal";
import { BoardLoader } from "../board-loader";
import { EventRoomSubpageHeader } from "../room-subpage-header";

/** Forms and overrides do not change the event revision, so this page asks for fresh scores on its own. */
const REFRESH_MS = 5000;
const STATUS_LABELS: Record<TerminalDashboardStatus, string> = {
  NOT_STARTED: "Not started",
  SAVED: "Saved",
  SUBMITTED: "Submitted",
};
const STATUS_VARIANTS: Record<TerminalDashboardStatus, UiStatusChipVariant> = {
  NOT_STARTED: "neutral",
  SAVED: "warning",
  SUBMITTED: "success",
};
type Decision = "auto" | "award" | "deny";

/**
 * /event/room/:roomId/dashboard: the Linux room's organizer page for its manager (or ADMIN). The answer keys (one
 * value per line for the four parameters), every active team's form with a tick or cross per item, an award/deny
 * override per item, the score out of 15 and the submit time, the spreadsheet export, and "Test terminal" with the
 * room's current package. Scores are computed by the server on every read; they stay inside this room.
 */
@Component({
  imports: [EventRoomSubpageHeader, FormsModule, EventLinuxTerminal, UiAlert, UiButton, UiCard, UiPageState, UiStatusChip, UiTextarea],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./terminal-dashboard.page.css",
  templateUrl: "./terminal-dashboard.page.html",
})
export class EventTerminalDashboardPage {
  private readonly eventApi = inject(EventApi);
  protected readonly roomId = signal(0);
  protected readonly loader = new BoardLoader<TerminalDashboard>(() => this.eventApi.terminalDashboard(this.roomId()));
  protected readonly dashboard = this.loader.data;
  protected readonly statusLabels = STATUS_LABELS;
  protected readonly statusVariants = STATUS_VARIANTS;
  protected readonly formatTime = formatTime;
  protected readonly updated = computed(() => formatTime(this.dashboard()?.generatedAt ?? null));

  protected readonly keysDraft = signal<TerminalAnswerKeys | null>(null);
  protected readonly keysBusy = signal(false);
  protected readonly keysNotice = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly busyOverride = signal<string | null>(null);
  protected readonly testing = signal(false);
  protected readonly loadTestFiles = () => this.eventApi.terminalStartFiles(this.roomId());
  protected readonly loadTestBundle = () => this.eventApi.terminalBundle(this.roomId(), "manager");

  /** The keys on screen differ from the saved ones. */
  protected readonly keysDirty = computed(() => {
    const draft = this.keysDraft();
    const saved = this.dashboard()?.keys;
    return !!draft && !!saved && (Object.keys(saved) as (keyof TerminalAnswerKeys)[]).some((key) => draft[key] !== saved[key]);
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
        this.keysDraft.set(null);
        void this.refresh();
      });
    const timer = setInterval(() => {
      if (this.roomId() > 0 && document.visibilityState === "visible") void this.refresh();
    }, REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected reload(): void {
    void this.refresh();
  }

  protected setKey(key: keyof TerminalAnswerKeys, value: string): void {
    this.keysDraft.update((draft) => draft && { ...draft, [key]: value });
  }

  protected async saveKeys(): Promise<void> {
    const draft = this.keysDraft();
    if (!draft || this.keysBusy()) return;
    this.keysBusy.set(true);
    this.actionError.set(null);
    this.keysNotice.set(null);
    try {
      const saved = await this.eventApi.saveTerminalKeys(this.roomId(), draft);
      this.loader.data.set(saved);
      this.keysDraft.set({ ...saved.keys });
      this.keysNotice.set("Answer keys saved. Scores below follow them at once.");
    } catch (e) {
      this.actionError.set(message(e));
    } finally {
      this.keysBusy.set(false);
    }
  }

  protected async decide(team: TerminalDashboardTeam, item: TerminalItemScore, decision: Decision): Promise<void> {
    if (this.busyOverride()) return;
    this.busyOverride.set(`${team.teamId}:${item.item}`);
    this.actionError.set(null);
    try {
      const awarded = decision === "auto" ? null : decision === "award";
      this.loader.data.set(await this.eventApi.setTerminalOverride(this.roomId(), team.teamId, item.item, awarded));
    } catch (e) {
      this.actionError.set(message(e));
      await this.refresh();
    } finally {
      this.busyOverride.set(null);
    }
  }

  protected decisionOf(item: TerminalItemScore): Decision {
    return item.override === null ? "auto" : item.override ? "award" : "deny";
  }

  /** The submitted text for one scored item (the RETRIEVED KEY item shows the retrieved key and whether it was right). */
  protected valuesOf(team: TerminalDashboardTeam, item: TerminalScoreItem): { text: string; ok: boolean | null }[] {
    switch (item) {
      case "PATH":
        return [{ text: team.path, ok: null }];
      case "USERNAME":
        return [{ text: team.username, ok: null }];
      case "FILE_SIZE":
        return [{ text: team.fileSize, ok: null }];
      case "PATTERN":
        return [{ text: team.pattern, ok: null }];
      case "CLUE":
        return [{ text: team.finalClue, ok: team.clueCorrect }];
    }
  }

  protected async exportCsv(): Promise<void> {
    this.actionError.set(null);
    try {
      await this.eventApi.downloadTerminalDashboardCsv(this.roomId());
    } catch (e) {
      this.actionError.set(message(e));
    }
  }

  private async refresh(): Promise<void> {
    await this.loader.load();
    const loaded = this.loader.data();
    // The first load fills the key boxes; later refreshes never overwrite what the organizer is typing.
    if (loaded && this.keysDraft() === null) this.keysDraft.set({ ...loaded.keys });
    else if (loaded && !this.keysDirty()) this.keysDraft.set({ ...loaded.keys });
  }
}
