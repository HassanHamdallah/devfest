import { ChangeDetectionStrategy, Component, computed, effect, inject, input, untracked } from "@angular/core";
import { EventApi } from "../../../../core/event/event-api";
import { ContestTeamView, TeamBoard } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { BoardLoader } from "../../manager/board-loader";
import { EventMaskedClue } from "../masked-clue";

/**
 * The Problem Solving room of the team's current room (rules §20.3): the contest runs on Codeforces, so the team gets
 * only the link (while its timer runs or is paused) and, once the organizer releases it, the room's clue behind the
 * eye toggle. Everything comes from GET /team/rooms/{id}/contest and the board's evidence; nothing else is known to
 * the team (no count, points, penalty, place or eligibility exists in the response). The board's revision (timer
 * changes, the link, the release) fetches it again.
 */
@Component({
  selector: "event-contest-room-activity",
  imports: [EventMaskedClue, UiAlert, UiButton, UiCard, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./contest-room-activity.css",
  templateUrl: "./contest-room-activity.html",
  host: { class: "room-tone--PROBLEM_SOLVING" },
})
export class EventContestRoomActivity {
  private readonly eventApi = inject(EventApi);

  readonly board = input.required<TeamBoard>();

  private readonly roomId = computed(() => this.board().scheduledRoom?.roomId ?? null);
  protected readonly loader = new BoardLoader<ContestTeamView>(() => this.eventApi.teamContest(this.roomId() as number));
  protected readonly contest = this.loader.data;
  protected readonly phase = computed(() => this.board().clock.phase);
  /** The room's clue, present in the evidence only after the organizer released it. */
  protected readonly clue = computed(() => {
    const code = this.board().scheduledRoom?.code;
    return this.board().evidence.find((entry) => entry.roomCode === code)?.clueValue ?? null;
  });

  constructor() {
    effect(() => {
      this.board();
      if (this.roomId() !== null) untracked(() => void this.loader.load());
    });
  }

  protected reload(): void {
    void this.loader.load();
  }
}
