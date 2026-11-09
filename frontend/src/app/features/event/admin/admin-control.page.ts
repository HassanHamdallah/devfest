import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from "@angular/core";
import { EventApi } from "../../../core/event/event-api";
import { EventLiveStore } from "../../../core/event/event-live-store";
import { reloadOnRevisionChange } from "../../../core/event/revision-sync";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiConfirmDialog } from "../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { AlertsList } from "./control/alerts-list";
import { ControlBar } from "./control/control-bar";
import {
  ConfirmationCopy,
  ControlAction,
  RoomActionError,
  confirmationFor,
  performControlAction,
  successNotice,
} from "./control/control-actions";
import { RankingPanel } from "./control/ranking-panel";
import { RoomCards } from "./control/room-cards";
import { FinalTimerPanel } from "./control/final-timer-panel";
import { RoundTimerPanel } from "./control/round-timer-panel";
import { RotationTable } from "./control/rotation-table";
import { RoundSettings } from "./control/round-settings";
import { ActionState } from "./shared/action-state";
import { ActionStatus } from "./shared/action-status";
import { RemoteData } from "./shared/remote-data";

const NO_CONFIRMATION: ConfirmationCopy = {
  title: "",
  message: "",
  variant: "primary",
};

/** Organizer control room: rounds, rooms, final, ranking and setup alerts from GET /admin/board. */
@Component({
  imports: [
    ActionStatus,
    AlertsList,
    ControlBar,
    RankingPanel,
    RoomCards,
    FinalTimerPanel,
    RoundTimerPanel,
    RotationTable,
    RoundSettings,
    UiAlert,
    UiConfirmDialog,
    UiPageState,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: "./admin-control.page.html",
})
export class EventAdminControlPage {
  private readonly eventApi = inject(EventApi);

  protected readonly board = new RemoteData(() => this.eventApi.adminBoard());
  protected readonly actionState = new ActionState();
  /** Why the last "Open room" failed (e.g. no clue key yet), shown on that room's card. */
  protected readonly roomError = signal<RoomActionError | null>(null);
  private readonly pendingAction = signal<ControlAction | null>(null);

  protected readonly confirmOpen = computed(
    () => this.pendingAction() !== null,
  );
  protected readonly confirmation = computed(() => {
    const action = this.pendingAction();
    return (action && confirmationFor(action)) ?? NO_CONFIRMATION;
  });

  constructor() {
    const live = inject(EventLiveStore);
    effect(() => {
      const board = this.board.data();
      if (board) untracked(() => live.publishAdminBoard(board));
    });
    reloadOnRevisionChange(() => this.reload());
    this.reload();
  }

  protected reload(): void {
    void this.board.load();
  }

  protected request(action: ControlAction): void {
    if (this.actionState.busy()) return;
    if (confirmationFor(action)) this.pendingAction.set(action);
    else void this.execute(action);
  }

  protected async confirm(): Promise<void> {
    const action = this.pendingAction();
    if (!action) return;
    await this.execute(action);
    this.pendingAction.set(null);
  }

  protected cancel(): void {
    if (!this.actionState.busy()) this.pendingAction.set(null);
  }

  private async execute(action: ControlAction): Promise<void> {
    const ok = await this.actionState.run(
      () => performControlAction(this.eventApi, action),
      successNotice(action),
    );
    const failure = this.actionState.error();
    this.roomError.set(
      !ok && action.kind === "openRoom" && failure
        ? { roomId: action.room.id, message: failure }
        : null,
    );
    await this.board.load();
  }
}
