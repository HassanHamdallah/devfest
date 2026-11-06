import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  signal,
  untracked,
} from "@angular/core";
import { formatMinutesSeconds } from "../../../../core/event/countdown";
import { EventApi } from "../../../../core/event/event-api";
import { AiRoomActivity, TeamBoard } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { BoardLoader } from "../../manager/board-loader";
import { EventMaskedClue } from "../masked-clue";
import { EventQuizMcq } from "./quiz-mcq";
import { EventQuizRepairs } from "./quiz-repairs";
import { STAGE_TITLES, mcqSecondsLeft } from "./quiz-view";

/**
 * The AI room activity of the team's current room (rules §19): every state comes from
 * GET /team/rooms/{id}/activity, so a reload or a new sign-in resumes exactly where the team was. The board's
 * revision (timer changes, admin actions, the team's evaluation) and the end of the MCQ countdown fetch it again.
 * Once the team is evaluated only its outcome is shown: the clue behind the eye toggle, or that it was not reached.
 */
@Component({
  selector: "event-ai-room-activity",
  imports: [EventMaskedClue, EventQuizMcq, EventQuizRepairs, UiAlert, UiButton, UiCard, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./ai-room-activity.css",
  templateUrl: "./ai-room-activity.html",
  host: { class: "room-tone--AI" },
})
export class EventAiRoomActivity {
  private readonly eventApi = inject(EventApi);

  readonly board = input.required<TeamBoard>();

  private readonly roomId = computed(() => this.board().scheduledRoom?.roomId ?? null);
  protected readonly loader = new BoardLoader<AiRoomActivity>(() => this.eventApi.teamActivity(this.roomId() as number));
  protected readonly activity = this.loader.data;
  protected readonly titles = STAGE_TITLES;
  private readonly now = signal(Date.now());
  private readonly receivedAt = linkedSignal({ source: this.activity, computation: () => Date.now() });
  protected readonly mcqLeft = computed(() => {
    const activity = this.activity();
    return activity ? mcqSecondsLeft(activity, this.receivedAt(), this.now()) : 0;
  });
  protected readonly mcqLeftLabel = computed(() => formatMinutesSeconds(this.mcqLeft()));
  protected readonly paused = computed(() => this.activity()?.phase === "PAUSED");
  protected readonly readOnly = computed(() => this.paused() || this.board().disqualified || !this.board().scheduledRoom?.open);

  constructor() {
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
    // Every new board (revision change, own write) may carry a new timer state: fetch the activity again.
    effect(() => {
      this.board();
      if (this.roomId() !== null) untracked(() => void this.loader.load());
    });
    // The MCQ part closes on the server at the cutoff: fetch once the local countdown reaches it.
    effect(() => {
      const activity = this.activity();
      if (activity?.stage === "MCQ" && activity.phase === "RUNNING" && this.mcqLeft() === 0) {
        untracked(() => void this.loader.load());
      }
    });
  }

  protected reload(): void {
    void this.loader.load();
  }

  /** A write answered with the new activity (Finish MCQs). */
  protected replace(activity: AiRoomActivity): void {
    this.loader.data.set(activity);
  }
}
