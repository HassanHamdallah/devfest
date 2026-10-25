import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from "@angular/core";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { ActivatedRoute } from "@angular/router";
import { PHASE_LABELS } from "../../../core/event/countdown";
import { EventApi } from "../../../core/event/event-api";
import { EventLiveStore } from "../../../core/event/event-live-store";
import { RoomBoard, RoomTeamRow } from "../../../core/event/event-models";
import { PHASE_VARIANTS } from "../../../core/event/event-status";
import { roundStatusLabel } from "../../../core/event/format";
import { reloadOnRevisionChange } from "../../../core/event/revision-poller";
import { NavIcon } from "../../../core/shell/nav-icon";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiCountdown } from "../../../shared/ui/ui-countdown/ui-countdown";
import { UiEmptyState } from "../../../shared/ui/ui-empty-state/ui-empty-state";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { BoardLoader } from "./board-loader";
import { EventArrivalCard } from "./arrival-card";
import { EventClueKeyCard } from "./clue-key-card";
import { EventRefreshError } from "./refresh-error";
import { EventRoomContentCard } from "./room-content-card";
import { EventRoomTeamRow } from "./room-team-row";
import {
  FILTER_MATCHES,
  TEAM_FILTERS,
  TeamFilter,
  isAwaitingDecision,
  isPhoneWidth,
  nextPendingTeam,
  withNewPendingRows,
} from "./room-board-view";

/** /event/room/:roomId: the room manager's live board (an ADMIN may open any room). */
@Component({
  imports: [
    EventArrivalCard,
    EventClueKeyCard,
    EventRefreshError,
    EventRoomContentCard,
    EventRoomTeamRow,
    NavIcon,
    UiButton,
    UiCountdown,
    UiEmptyState,
    UiPageState,
    UiStatusChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./room-board.page.css",
  templateUrl: "./room-board.page.html",
})
export class EventRoomBoardPage {
  private readonly eventApi = inject(EventApi);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly roomId = signal<number | null>(null);

  protected readonly loader = new BoardLoader<RoomBoard>(() =>
    this.eventApi.roomBoard(this.roomId() as number),
  );
  protected readonly filters = TEAM_FILTERS;
  protected readonly phaseLabels = PHASE_LABELS;
  protected readonly phaseVariants = PHASE_VARIANTS;
  /** The manager's chosen tab; until then "Now" if any team is scheduled here this round, else "All". */
  private readonly chosenFilter = signal<TeamFilter | null>(null);
  /** Rows showing their details. Rows awaiting a decision open by themselves (one at a time on phones). */
  protected readonly expanded = signal<ReadonlySet<number>>(new Set());
  private seenSubmissions = new Set<string>();
  private lastJumped: number | null = null;

  protected readonly board = this.loader.data;
  protected readonly filter = computed<TeamFilter>(() => {
    const chosen = this.chosenFilter();
    if (chosen) return chosen;
    return this.board()?.teams.some((row) => row.scheduledNow) ? "NOW" : "ALL";
  });
  protected readonly counts = computed(() => {
    const teams = this.board()?.teams ?? [];
    return Object.fromEntries(
      TEAM_FILTERS.map((option) => [
        option.value,
        teams.filter(FILTER_MATCHES[option.value]).length,
      ]),
    ) as Record<TeamFilter, number>;
  });
  protected readonly visibleTeams = computed(() => {
    const matches = FILTER_MATCHES[this.filter()];
    return (this.board()?.teams ?? []).filter(matches);
  });
  protected readonly pendingCount = computed(
    () => (this.board()?.teams ?? []).filter(isAwaitingDecision).length,
  );
  /** Null while there are rows to show. */
  protected readonly emptyTeamsTitle = computed(() => {
    if (this.visibleTeams().length > 0) return null;
    return this.board()?.teams.length
      ? "No teams match this filter."
      : "No active teams yet.";
  });
  protected readonly roundLine = computed(() => {
    const board = this.board();
    if (!board) return "";
    const round = roundStatusLabel(board.clock.currentRound);
    return board.scheduledGroup
      ? `${round} · group ${board.scheduledGroup} in this room`
      : round;
  });

  constructor() {
    const live = inject(EventLiveStore);
    effect(() => {
      const board = this.board();
      if (!board) return;
      untracked(() => {
        live.publishRoomBoard(board);
        this.expanded.set(
          withNewPendingRows(board.teams, this.expanded(), this.seenSubmissions, isPhoneWidth()),
        );
      });
    });
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed())
      .subscribe((params) => this.showRoom(params.get("roomId")));
    reloadOnRevisionChange(() => void this.reload());
  }

  protected reload(): Promise<void> {
    return this.roomId() === null ? Promise.resolve() : this.loader.load();
  }

  protected chooseFilter(filter: TeamFilter): void {
    this.chosenFilter.set(filter);
  }

  protected toggle(row: RoomTeamRow): void {
    const open = this.expanded().has(row.teamId);
    const next = new Set(isPhoneWidth() ? [] : this.expanded());
    if (open) next.delete(row.teamId);
    else next.add(row.teamId);
    this.expanded.set(next);
  }

  /** Opens the next team awaiting a decision (after the last one jumped to), switching to "Pending" if hidden. */
  protected jumpToNextPending(): void {
    const teams = this.board()?.teams ?? [];
    const target = nextPendingTeam(teams, this.lastJumped);
    if (!target) return;
    this.lastJumped = target.teamId;
    if (!this.visibleTeams().includes(target)) this.chosenFilter.set("PENDING");
    const next = new Set(isPhoneWidth() ? [] : this.expanded());
    this.expanded.set(next.add(target.teamId));
    afterNextRender(
      () => {
        const element = this.host.nativeElement.querySelector<HTMLElement>(
          `[data-team-id="${target.teamId}"]`,
        );
        element?.scrollIntoView({ block: "start" });
        element?.querySelector<HTMLElement>(".summary__toggle")?.focus({ preventScroll: true });
      },
      { injector: this.injector },
    );
  }

  /** The component is reused when the admin moves between room links, so a new id starts fresh. */
  private showRoom(rawId: string | null): void {
    const id = Number(rawId);
    this.chosenFilter.set(null);
    this.expanded.set(new Set());
    this.seenSubmissions = new Set();
    this.lastJumped = null;
    if (!Number.isInteger(id) || id <= 0) {
      this.roomId.set(null);
      this.loader.fail("Room not found.");
      return;
    }
    this.roomId.set(id);
    this.loader.reset();
    void this.loader.load();
  }
}
