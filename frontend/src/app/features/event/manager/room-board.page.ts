import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from "@angular/core";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { ActivatedRoute } from "@angular/router";
import { EventApi } from "../../../core/event/event-api";
import { RoomBoard, RoomTeamRow } from "../../../core/event/event-models";
import { roundStatusLabel } from "../../../core/event/format";
import { reloadOnRevisionChange } from "../../../core/event/revision-poller";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiCountdown } from "../../../shared/ui/ui-countdown/ui-countdown";
import { UiEmptyState } from "../../../shared/ui/ui-empty-state/ui-empty-state";
import { UiMetricTile } from "../../../shared/ui/ui-metric-tile/ui-metric-tile";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { BoardLoader } from "./board-loader";
import { EventRefreshError } from "./refresh-error";
import { EventRoomContentCard } from "./room-content-card";
import { EventRoomTeamRow } from "./room-team-row";

export type TeamFilter = "ALL" | "PENDING" | "NOW";

export const TEAM_FILTERS: readonly { value: TeamFilter; label: string }[] = [
  { value: "ALL", label: "All" },
  { value: "PENDING", label: "Pending" },
  { value: "NOW", label: "Now" },
];

/** "Pending" means the same as the "Pending review" counter: submitted and not judged yet. */
const FILTER_MATCHES: Record<TeamFilter, (row: RoomTeamRow) => boolean> = {
  ALL: () => true,
  PENDING: (row) =>
    row.latestSubmission !== null && row.judgement.status === "PENDING",
  NOW: (row) => row.scheduledNow,
};

/** /event/room/:roomId: the room manager's live board (an ADMIN may open any room). */
@Component({
  imports: [
    EventRefreshError,
    EventRoomContentCard,
    EventRoomTeamRow,
    UiButton,
    UiCountdown,
    UiEmptyState,
    UiMetricTile,
    UiPageState,
    UiStatusChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .teams {
      display: grid;
      gap: var(--space-3);
    }
  `,
  templateUrl: "./room-board.page.html",
})
export class EventRoomBoardPage {
  private readonly eventApi = inject(EventApi);
  private readonly roomId = signal<number | null>(null);

  protected readonly loader = new BoardLoader<RoomBoard>(() =>
    this.eventApi.roomBoard(this.roomId() as number),
  );
  protected readonly filters = TEAM_FILTERS;
  protected readonly filter = signal<TeamFilter>("ALL");

  protected readonly board = this.loader.data;
  protected readonly visibleTeams = computed(() => {
    const matches = FILTER_MATCHES[this.filter()];
    return (this.board()?.teams ?? []).filter(matches);
  });
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
  protected readonly inRoomHint = computed(() => {
    const group = this.board()?.scheduledGroup;
    return group ? `Group ${group}` : "No group scheduled";
  });

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed())
      .subscribe((params) => this.showRoom(params.get("roomId")));
    reloadOnRevisionChange(() => void this.reload());
  }

  protected reload(): Promise<void> {
    return this.roomId() === null ? Promise.resolve() : this.loader.load();
  }

  /** The component is reused when the admin moves between room links, so a new id starts fresh. */
  private showRoom(rawId: string | null): void {
    const id = Number(rawId);
    if (!Number.isInteger(id) || id <= 0) {
      this.roomId.set(null);
      this.loader.fail("Room not found.");
      return;
    }
    this.roomId.set(id);
    this.filter.set("ALL");
    this.loader.reset();
    void this.loader.load();
  }
}
