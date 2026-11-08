import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from "@angular/core";
import { EventApi } from "../../../../core/event/event-api";
import { AiRoomActivity, SentinelStage, TeamBoard } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiCountdown } from "../../../../shared/ui/ui-countdown/ui-countdown";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { BoardLoader } from "../../manager/board-loader";
import { EventMaskedClue } from "../masked-clue";
import { SentinelStageCamera } from "./stage-camera";
import { SentinelStageFeatures } from "./stage-features";
import { SentinelStageGroups } from "./stage-groups";
import { SentinelStageNearest } from "./stage-nearest";
import { SentinelStageOutlier } from "./stage-outlier";
import { SentinelStageRag } from "./stage-rag";
import { SentinelStageRoute } from "./stage-route";
import { SentinelStageRules } from "./stage-rules";
import { SentinelStageThreat } from "./stage-threat";
import { SentinelStageTune } from "./stage-tune";

/** The three systems, as shown before the timer starts (the server sends the missions only while the round runs). */
const SYSTEMS = [
  { id: "predict", title: "PREDICT", about: "Outliers, features and model tuning" },
  { id: "see", title: "SEE", about: "Neighbours, camera accuracy and grouping" },
  { id: "noor", title: "NOOR", about: "Routes, threats, rules and retrieval" },
];

/**
 * SENTINEL, the AI room's mission (rules §19): every state comes from GET /team/rooms/{id}/activity, so a reload
 * or a new sign-in resumes exactly where the team was (saved stages, experiments already run). The board's revision
 * (timer changes, admin actions, the team's evaluation) fetches it again. All ten stages stay on the page, so
 * moving between stages never loses work in progress; a saved stage shows only "Saved". Once the team is evaluated
 * only the ending is shown: the clue behind the eye toggle, or that the system stayed unstable.
 */
@Component({
  selector: "event-sentinel-activity",
  imports: [
    EventMaskedClue,
    SentinelStageCamera,
    SentinelStageFeatures,
    SentinelStageGroups,
    SentinelStageNearest,
    SentinelStageOutlier,
    SentinelStageRag,
    SentinelStageRoute,
    SentinelStageRules,
    SentinelStageThreat,
    SentinelStageTune,
    UiAlert,
    UiButton,
    UiCard,
    UiCountdown,
    UiStatusChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./sentinel-activity.css",
  templateUrl: "./sentinel-activity.html",
  host: { class: "room-tone--AI" },
})
export class EventSentinelActivity {
  private readonly eventApi = inject(EventApi);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly stageNav = viewChild<ElementRef<HTMLElement>>("stageNav");

  readonly board = input.required<TeamBoard>();

  private readonly roomId = computed(() => this.board().scheduledRoom?.roomId ?? null);
  protected readonly loader = new BoardLoader<AiRoomActivity>(() => this.eventApi.teamActivity(this.roomId() as number));
  protected readonly activity = this.loader.data;
  protected readonly systems = SYSTEMS;
  protected readonly paused = computed(() => this.activity()?.phase === "PAUSED");
  protected readonly readOnly = computed(() => this.paused() || this.board().disqualified || !this.board().scheduledRoom?.open);
  protected readonly stages = computed<SentinelStage[]>(() => this.activity()?.missions.flatMap((m) => m.stages) ?? []);
  private readonly hasWork = computed(() => (this.activity()?.savedCount ?? 0) > 0 || this.stages().some((s) => s.runs.length > 0));
  private readonly begunKey = computed(() => `devfest.sentinel.begun.${this.roomId()}.${this.board().clock.currentRound}`);
  protected readonly begun = signal(false);
  /** The story and the three systems, until the team starts (or already has work, after a reload). */
  protected readonly showStart = computed(() => {
    const view = this.activity();
    return !!view && view.ready && view.result === null && view.phase !== "TIME_UP" && !this.begun() && !this.hasWork();
  });
  private readonly picked = signal<number | null>(null);
  /** The stage on screen: the team's pick, else the first one not saved yet. */
  protected readonly current = computed<SentinelStage | null>(() => {
    const all = this.stages();
    return all.find((s) => s.number === this.picked()) ?? all.find((s) => !s.saved) ?? all[0] ?? null;
  });
  protected readonly nextOpen = computed(() => {
    const now = this.current()?.number ?? 0;
    const open = this.stages().filter((s) => !s.saved);
    return open.find((s) => s.number > now) ?? open[0] ?? null;
  });

  constructor() {
    effect(() => {
      try {
        if (this.roomId() !== null && sessionStorage.getItem(this.begunKey()) === "1") this.begun.set(true);
      } catch {
        // Storage may be blocked; the start screen then shows again after a reload when nothing was done yet.
      }
    });
    // Every new board (revision change, own write) may carry a new timer state: fetch the activity again.
    effect(() => {
      this.board();
      if (this.roomId() !== null) untracked(() => void this.loader.load());
    });
  }

  protected reload(): void {
    void this.loader.load();
  }

  protected begin(): void {
    this.begun.set(true);
    try {
      sessionStorage.setItem(this.begunKey(), "1");
    } catch {
      // Not persisting is harmless.
    }
  }

  /** Shows the stage and brings it to the top of the screen, so its plot and controls are in view at once. */
  protected pick(number: number): void {
    this.picked.set(number);
    afterNextRender(() => this.host.nativeElement.querySelector(`[data-stage="${number}"]`)?.scrollIntoView({ block: "start", behavior: smooth() }), {
      injector: this.injector,
    });
  }

  protected toNav(): void {
    this.stageNav()?.nativeElement.scrollIntoView({ block: "start", behavior: smooth() });
  }

  protected missionOf(stage: SentinelStage): string {
    return this.activity()?.missions.find((m) => m.stages.includes(stage))?.title ?? "";
  }

  protected savedIn(missionIndex: number): number {
    return this.activity()?.missions[missionIndex]?.stages.filter((s) => s.saved).length ?? 0;
  }
}

/** Smooth scrolling unless the visitor asked for less motion. */
function smooth(): ScrollBehavior {
  return matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth";
}
