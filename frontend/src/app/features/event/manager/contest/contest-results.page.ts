import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from "@angular/core";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { FormsModule } from "@angular/forms";
import { ActivatedRoute, RouterLink } from "@angular/router";
import { message } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import {
  CONTEST_PROBLEMS,
  ContestProblem,
  ContestResults,
  ContestRound,
  ContestTeamRow,
} from "../../../../core/event/event-models";
import { formatTime } from "../../../../core/event/format";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { UiPageState } from "../../../../shared/ui/ui-page-state/ui-page-state";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { BoardLoader } from "../board-loader";
import { EventRoomSubpageHeader } from "../room-subpage-header";

/** Results do not change the event revision, so this page asks for fresh numbers on its own while it is open. */
const REFRESH_MS = 5000;
const WHOLE_MINUTES = /^\d{1,6}$/;
const MAX_PENALTY = 100000;

/** What the organizer has typed for one team and not saved yet. */
interface Draft {
  solved: ContestProblem[];
  penalty: string;
}

/**
 * /event/room/:roomId/contest: the Problem Solving room's organizer page for its manager (or ADMIN). The contest link,
 * the teams grouped by round with a checkbox per problem A to E and a penalty in whole minutes (typed from the
 * Codeforces standings, saved per team), the scores the server computes on every read, the clue release per team and
 * per round, and the spreadsheet export. Nothing here is ever sent to the teams except the link and the released clue.
 */
@Component({
  imports: [EventRoomSubpageHeader, FormsModule, RouterLink, UiAlert, UiButton, UiCard, UiInput, UiPageState, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./contest-results.page.css",
  templateUrl: "./contest-results.page.html",
})
export class EventContestResultsPage {
  private readonly eventApi = inject(EventApi);
  protected readonly roomId = signal(0);
  protected readonly loader = new BoardLoader<ContestResults>(() => this.eventApi.contestResults(this.roomId()));
  protected readonly results = this.loader.data;
  protected readonly problems = CONTEST_PROBLEMS;
  protected readonly formatTime = formatTime;
  protected readonly updated = computed(() => formatTime(this.results()?.generatedAt ?? null));

  protected readonly linkDraft = signal<string | null>(null);
  protected readonly linkBusy = signal(false);
  protected readonly linkNotice = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  /** "save:7", "reveal:7" or "round:2": the one write in flight. */
  protected readonly busy = signal<string | null>(null);
  private readonly drafts = signal<Record<number, Draft>>({});

  protected readonly linkDirty = computed(() => {
    const draft = this.linkDraft();
    const saved = this.results()?.contestUrl ?? "";
    return draft !== null && draft.trim() !== saved;
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
        this.linkDraft.set(null);
        this.drafts.set({});
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

  // ---- the link ----

  protected async saveLink(): Promise<void> {
    const url = (this.linkDraft() ?? "").trim();
    if (this.linkBusy() || !url) return;
    this.linkBusy.set(true);
    this.actionError.set(null);
    this.linkNotice.set(null);
    try {
      const saved = await this.eventApi.saveContestLink(this.roomId(), url);
      this.loader.data.set(saved);
      this.linkDraft.set(saved.contestUrl ?? "");
      this.linkNotice.set("Contest link saved. The assigned team sees it while its timer runs.");
    } catch (e) {
      this.actionError.set(message(e));
    } finally {
      this.linkBusy.set(false);
    }
  }

  protected async clearLink(): Promise<void> {
    if (this.linkBusy()) return;
    this.linkBusy.set(true);
    this.actionError.set(null);
    this.linkNotice.set(null);
    try {
      const saved = await this.eventApi.clearContestLink(this.roomId());
      this.loader.data.set(saved);
      this.linkDraft.set("");
      this.linkNotice.set("Contest link removed.");
    } catch (e) {
      this.actionError.set(message(e));
    } finally {
      this.linkBusy.set(false);
    }
  }

  // ---- results ----

  protected solvedIn(row: ContestTeamRow, problem: ContestProblem): boolean {
    return this.draftOf(row).solved.includes(problem);
  }

  protected penaltyOf(row: ContestTeamRow): string {
    return this.draftOf(row).penalty;
  }

  protected toggle(row: ContestTeamRow, problem: ContestProblem, checked: boolean): void {
    const draft = this.draftOf(row);
    const solved = checked ? [...draft.solved.filter((p) => p !== problem), problem] : draft.solved.filter((p) => p !== problem);
    this.setDraft(row, { ...draft, solved: CONTEST_PROBLEMS.filter((p) => solved.includes(p)) });
  }

  protected setPenalty(row: ContestTeamRow, penalty: string): void {
    this.setDraft(row, { ...this.draftOf(row), penalty });
  }

  protected dirty(row: ContestTeamRow): boolean {
    const draft = this.drafts()[row.teamId];
    if (!draft) return false;
    const saved = this.saved(row);
    return draft.penalty !== saved.penalty || draft.solved.join() !== saved.solved.join() || !row.saved;
  }

  protected penaltyValid(row: ContestTeamRow): boolean {
    const text = this.penaltyOf(row).trim();
    return WHOLE_MINUTES.test(text) && Number(text) <= MAX_PENALTY;
  }

  protected discard(row: ContestTeamRow): void {
    this.drafts.update(({ [row.teamId]: _, ...rest }) => rest);
  }

  protected async save(row: ContestTeamRow): Promise<void> {
    if (this.busy() || !this.penaltyValid(row)) return;
    const draft = this.draftOf(row);
    await this.write(`save:${row.teamId}`, async () => {
      const saved = await this.eventApi.saveContestResult(this.roomId(), row.teamId, {
        problems: draft.solved,
        penaltyMinutes: Number(draft.penalty.trim()),
      });
      this.discard(row);
      return saved;
    });
  }

  // ---- the clue release ----

  protected async reveal(row: ContestTeamRow): Promise<void> {
    if (this.busy()) return;
    await this.write(`reveal:${row.teamId}`, async () => {
      const result = await this.eventApi.revealContestClue(this.roomId(), row.teamId);
      this.notice.set(`${row.teamName}: clue released.`);
      return result;
    });
  }

  protected async revealRound(round: ContestRound): Promise<void> {
    if (this.busy()) return;
    await this.write(`round:${round.round}`, async () => {
      const result = await this.eventApi.revealContestRound(this.roomId(), round.round);
      const after = result.rounds.find((candidate) => candidate.round === round.round);
      const released = (after?.releasedCount ?? 0) - round.releasedCount;
      this.notice.set(
        released > 0
          ? `Round ${round.round}: clue released to ${released} ${released === 1 ? "team" : "teams"}.`
          : `Round ${round.round}: no eligible team was waiting.`,
      );
      return result;
    });
  }

  protected canReveal(row: ContestTeamRow): boolean {
    return row.saved && row.eligible && !row.released && !this.dirty(row) && (this.results()?.clueKeySet ?? false);
  }

  protected revealHint(row: ContestTeamRow): string {
    if (!row.saved) return "No results saved yet.";
    if (!row.eligible) return `Needs at least ${this.results()?.eligibleSolved ?? 3} solved.`;
    if (this.dirty(row)) return "Save the results first.";
    if (!this.results()?.clueKeySet) return "Set the room's clue key first.";
    return "";
  }

  protected async exportCsv(): Promise<void> {
    this.actionError.set(null);
    try {
      await this.eventApi.downloadContestResultsCsv(this.roomId());
    } catch (e) {
      this.actionError.set(message(e));
    }
  }

  // ---- helpers ----

  private saved(row: ContestTeamRow): Draft {
    return { solved: [...row.solved], penalty: String(row.penaltyMinutes) };
  }

  private draftOf(row: ContestTeamRow): Draft {
    return this.drafts()[row.teamId] ?? this.saved(row);
  }

  private setDraft(row: ContestTeamRow, draft: Draft): void {
    this.drafts.update((all) => ({ ...all, [row.teamId]: draft }));
  }

  private async write(key: string, run: () => Promise<ContestResults>): Promise<void> {
    this.busy.set(key);
    this.actionError.set(null);
    this.notice.set(null);
    try {
      this.loader.data.set(await run());
    } catch (e) {
      this.actionError.set(message(e));
      await this.refresh();
    } finally {
      this.busy.set(null);
    }
  }

  private async refresh(): Promise<void> {
    await this.loader.load();
    const loaded = this.loader.data();
    // The first load fills the link box; later refreshes never overwrite what the organizer is typing.
    if (loaded && (this.linkDraft() === null || !this.linkDirty())) this.linkDraft.set(loaded.contestUrl ?? "");
  }
}
