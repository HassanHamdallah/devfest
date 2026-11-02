import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
} from "@angular/core";
import { EventApi } from "../../../core/event/event-api";
import { Receipt, TeamBoard } from "../../../core/event/event-models";
import { reloadOnRevisionChange } from "../../../core/event/revision-sync";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { BoardLoader } from "../manager/board-loader";
import { EventRefreshError } from "../manager/refresh-error";
import { EventRoomSubmissionPanel } from "./room-submission-panel";
import { currentRoomLine } from "./submission-availability";
import { EventTeamAnswerHistory } from "./team-answer-history";
import { EventTeamFinalCard } from "./team-final-card";
import { EventTeamHero } from "./team-hero";
import { EventTeamRoomCard } from "./team-room-card";

/**
 * /event/team: where the team must be now and its clues on top, the answer form for the current room right
 * under it, then the four rooms, the way to the final and the answer history.
 */
@Component({
  imports: [
    EventRefreshError,
    EventRoomSubmissionPanel,
    EventTeamAnswerHistory,
    EventTeamFinalCard,
    EventTeamHero,
    EventTeamRoomCard,
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
