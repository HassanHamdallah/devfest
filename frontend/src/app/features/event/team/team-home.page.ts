import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
} from "@angular/core";
import { EventApi } from "../../../core/event/event-api";
import {
  Receipt,
  RoomActivity,
  RoomCode,
  RoomEvidence,
  TeamBoard,
} from "../../../core/event/event-models";
import { reloadOnRevisionChange } from "../../../core/event/revision-sync";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { BoardLoader } from "../manager/board-loader";
import { EventRefreshError } from "../manager/refresh-error";
import { EventTeamRoomWorkspace } from "./room-workspace";
import { currentRoomLine } from "./submission-availability";
import { EventTeamAnswerHistory } from "./team-answer-history";
import { EventTeamEvidenceCard } from "./team-evidence-card";
import { EventTeamFinalCard } from "./team-final-card";
import { EventTeamHero } from "./team-hero";
import { EventTeamRoomCard } from "./team-room-card";

/**
 * /event/team: where the team must be now and its clues on top, the answer form for the current room right
 * under it, then the four rooms, the evidence of finished rooms, the way to the final and the answer history.
 */
@Component({
  imports: [
    EventRefreshError,
    EventTeamAnswerHistory,
    EventTeamEvidenceCard,
    EventTeamFinalCard,
    EventTeamHero,
    EventTeamRoomCard,
    EventTeamRoomWorkspace,
    UiPageState,
  ],
  styles: `
    .team-home {
      gap: var(--space-5);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: "./team-home.page.html",
})
export class EventTeamHomePage {
  private readonly eventApi = inject(EventApi);

  protected readonly loader = new BoardLoader<TeamBoard>(() =>
    this.eventApi.teamBoard(),
  );
  // Receipts change with this page's own submissions and when a manager re-checks answers (revision bump).
  protected readonly receipts = new BoardLoader<Receipt[]>(() =>
    this.eventApi.teamSubmissions(),
  );
  protected readonly board = this.loader.data;
  protected readonly receiptList = computed(() => this.receipts.data() ?? []);
  protected readonly receiptsError = computed(
    () => this.receipts.initialError() ?? this.receipts.refreshError(),
  );
  protected readonly roomLine = computed(() => {
    const board = this.board();
    return board ? currentRoomLine(board) : "";
  });
  protected readonly cluesLine = computed(
    () => `${this.board()?.cluesCollected ?? 0} of 4 clues collected`,
  );
  /** Rooms whose round is over for this team; until then the room behaves as before (form, receipt, history). */
  protected readonly finishedEvidence = computed(() =>
    (this.board()?.evidence ?? []).filter((evidence) => evidence.finished),
  );

  /** The single missing clue of a team with 3 clues can still be won back by the recovery challenge. */
  /** The room's activity from the board's evidence (the room cards carry none). */
  protected activityOf(board: TeamBoard, code: RoomCode): RoomActivity | null {
    return board.evidence.find((entry) => entry.roomCode === code)?.activity ?? null;
  }

  protected isRecoverable(board: TeamBoard, evidence: RoomEvidence): boolean {
    return (
      !evidence.clueCollected &&
      board.cluesCollected === 3 &&
      (board.finalStage === "LOCKED" || board.finalStage === "RECOVERY")
    );
  }

  constructor() {
    reloadOnRevisionChange(() => this.reloadAll());
    void this.loader.load();
    void this.receipts.load();
  }

  protected reloadAll(): void {
    void this.loader.load();
    void this.receipts.load();
  }
}
