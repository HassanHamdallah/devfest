import { ChangeDetectionStrategy, Component, inject } from "@angular/core";
import { Router } from "@angular/router";
import { EventApi } from "../../../core/event/event-api";
import { EventHome } from "../../../core/event/event-models";
import { reloadOnRevisionChange } from "../../../core/event/revision-poller";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { BoardLoader } from "./board-loader";

/** /event/room: sends a manager to their own room, or explains that none is assigned yet. */
@Component({
  imports: [UiCard, UiPageState],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<header class="event-page-header"><h1>My room</h1></header>
    <ui-page-state
      [loading]="loader.loading()"
      loadingLabel="Finding your room…"
      [error]="loader.initialError()"
      (retry)="load()"
    />
    @if (loader.data(); as home) {
      @if (home.roomId === null) {
        <ui-card>
          <p>No room is assigned to your account yet.</p>
          <p class="event-muted">
            An organizer assigns each room manager to one room. This page opens
            your room as soon as that happens.
          </p>
        </ui-card>
      }
    }`,
})
export class EventManagerHomePage {
  private readonly eventApi = inject(EventApi);
  private readonly router = inject(Router);
  protected readonly loader = new BoardLoader<EventHome>(() =>
    this.eventApi.home(),
  );

  constructor() {
    reloadOnRevisionChange(() => void this.load());
    void this.load();
  }

  protected async load(): Promise<void> {
    await this.loader.load();
    const roomId = this.loader.data()?.roomId ?? null;
    if (roomId !== null) {
      await this.router.navigateByUrl(`/event/room/${roomId}`, {
        replaceUrl: true,
      });
    }
  }
}
