import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
} from "@angular/core";
import { RouterLink } from "@angular/router";
import { EventApi } from "../../../core/event/event-api";
import { TeamFinal } from "../../../core/event/event-models";
import { reloadOnRevisionChange } from "../../../core/event/revision-sync";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { BoardLoader } from "../manager/board-loader";
import { EventRefreshError } from "../manager/refresh-error";
import { EventFinalEvidence } from "./final-evidence";
import { terminalSnapshots } from "../terminal/terminal-snapshot";
import { EventFinalCase } from "./final/final-case";
import { EventFinalMission } from "./final/final-mission";
import { EventFinalRecovery } from "./final/final-recovery";
import { EventFinalStatus } from "./final/final-status";
import { EventFinalSubmitted } from "./final/final-submitted";
import { EventFinalUnlock } from "./final/final-unlock";

/**
 * /event/team/final (a lazy chunk): renders exactly the screen the server reports for this team, in the site's own look.
 * The state always comes from the server, so a refresh resumes where the team was. When the final's timer reaches zero the page asks
 * the server once more, because the server alone finalises the team.
 */
@Component({
  imports: [
    EventFinalCase,
    EventFinalEvidence,
    EventFinalMission,
    EventFinalRecovery,
    EventFinalStatus,
    EventFinalSubmitted,
    EventFinalUnlock,
    EventRefreshError,
    RouterLink,
    UiCard,
    UiPageState,
    UiStatusChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: "./team-final.page.html",
  styleUrl: "./team-final.page.css",
})
export class EventTeamFinalPage {
  private readonly eventApi = inject(EventApi);

  protected readonly loader = new BoardLoader<TeamFinal>(() => this.eventApi.teamFinal());
  protected readonly final = this.loader.data;
  /** "Room clues" in every state except before the final opens. */
  protected readonly showsEvidence = computed(() => {
    const status = this.final()?.status;
    return status !== undefined && status !== "LOCKED";
  });
  protected readonly cluesLine = computed(
    () => `Your team collected ${this.final()?.cluesCollected ?? 0} of 4 clues.`,
  );

  constructor() {
    reloadOnRevisionChange(() => void this.reload());
    void this.reload();
    this.reloadWhenTimeIsUp();
    // Once the team's final is over its saved case terminal is of no use (a room's saved terminal is left alone).
    effect(() => {
      const screen = this.final()?.screen;
      if (screen === "SUBMITTED" || screen === "NOT_QUALIFIED") void terminalSnapshots.clearPlace("final");
    });
  }

  protected reload(): Promise<void> {
    return this.loader.load();
  }

  private reloadWhenTimeIsUp(): void {
    let timer: ReturnType<typeof setTimeout> | null = null;
    inject(DestroyRef).onDestroy(() => {
      if (timer !== null) clearTimeout(timer);
    });
    effect(() => {
      const view = this.final();
      if (timer !== null) clearTimeout(timer);
      timer = null;
      if (view?.timerPhase === "RUNNING" && view.status === "IN_FINAL" && view.remainingSeconds !== null) {
        timer = setTimeout(() => void this.reload(), (view.remainingSeconds + 1) * 1000);
      }
    });
  }
}
