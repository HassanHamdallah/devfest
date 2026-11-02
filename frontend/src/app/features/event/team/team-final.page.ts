import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
} from "@angular/core";
import { EventApi } from "../../../core/event/event-api";
import { FinalStage, TeamFinal } from "../../../core/event/event-models";
import { reloadOnRevisionChange } from "../../../core/event/revision-sync";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { BoardLoader } from "../manager/board-loader";
import { EventRefreshError } from "../manager/refresh-error";
import { EventFinalAccusationForm } from "./final-accusation-form";
import { EventFinalEvidence } from "./final-evidence";
import { EventFinalFinished } from "./final-finished";
import { EventFinalRecoveryForm } from "./final-recovery-form";

const FINAL_STEPS: Partial<Record<FinalStage, number>> = {
  RECOVERY: 1,
  MESSAGE: 2,
  FINISHED: 3,
};

/** /event/team/final: renders exactly the stage the server reports for this team, one primary action each. */
@Component({
  imports: [
    EventFinalAccusationForm,
    EventFinalEvidence,
    EventFinalFinished,
    EventFinalRecoveryForm,
    EventRefreshError,
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

  protected readonly loader = new BoardLoader<TeamFinal>(() =>
    this.eventApi.teamFinal(),
  );
  protected readonly final = this.loader.data;
  protected readonly steps = ["Recover a missing clue", "Decrypt and accuse", "Case solved"];
  /** 1-based step of the final the team is on; null outside the playable stages. */
  protected readonly step = computed(() => {
    const stage = this.final()?.stage;
    return stage ? (FINAL_STEPS[stage] ?? null) : null;
  });
  /** "Your evidence" in every stage except before the final opens and after it closes. */
  protected readonly showsEvidence = computed(() => {
    const stage = this.final()?.stage;
    return stage !== undefined && stage !== "LOCKED" && stage !== "CLOSED";
  });
  protected readonly cluesLine = computed(
    () =>
      `Your team collected ${this.final()?.cluesCollected ?? 0} of 4 clues.`,
  );

  constructor() {
    reloadOnRevisionChange(() => void this.reload());
    void this.reload();
  }

  protected reload(): Promise<void> {
    return this.loader.load();
  }
}
