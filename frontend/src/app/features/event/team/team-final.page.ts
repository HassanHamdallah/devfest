import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
} from "@angular/core";
import { EventApi } from "../../../core/event/event-api";
import { TeamFinal } from "../../../core/event/event-models";
import { reloadOnRevisionChange } from "../../../core/event/revision-poller";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { BoardLoader } from "../manager/board-loader";
import { EventRefreshError } from "../manager/refresh-error";
import { EventFinalAccusationForm } from "./final-accusation-form";
import { EventFinalFinished } from "./final-finished";
import { EventFinalRecoveryForm } from "./final-recovery-form";

/** /event/team/final: renders exactly the stage the server reports for this team. */
@Component({
  imports: [
    EventFinalAccusationForm,
    EventFinalFinished,
    EventFinalRecoveryForm,
    EventRefreshError,
    UiCard,
    UiPageState,
    UiStatusChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: "./team-final.page.html",
})
export class EventTeamFinalPage {
  private readonly eventApi = inject(EventApi);

  protected readonly loader = new BoardLoader<TeamFinal>(() =>
    this.eventApi.teamFinal(),
  );
  protected readonly final = this.loader.data;
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
