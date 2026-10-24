import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
} from "@angular/core";
import { EventApi } from "../../../core/event/event-api";
import { Receipt, TeamBoard } from "../../../core/event/event-models";
import { reloadOnRevisionChange } from "../../../core/event/revision-poller";
import { UiCountdown } from "../../../shared/ui/ui-countdown/ui-countdown";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { BoardLoader } from "../manager/board-loader";
import { EventRefreshError } from "../manager/refresh-error";
import { EventRoomSubmissionPanel } from "./room-submission-panel";
import { currentRoomLine } from "./submission-availability";
import { EventTeamAnswerHistory } from "./team-answer-history";
import { EventTeamFinalCard } from "./team-final-card";
import { EventTeamRoomCard } from "./team-room-card";

/** /event/team: the team's rooms, the answer form for the current room, receipts and the way to the final. */
@Component({
  imports: [
    EventRefreshError,
    EventRoomSubmissionPanel,
    EventTeamAnswerHistory,
    EventTeamFinalCard,
    EventTeamRoomCard,
    UiCountdown,
    UiPageState,
    UiStatusChip,
  ],
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
