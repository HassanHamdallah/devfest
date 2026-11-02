import {
  ChangeDetectionStrategy,
  Component,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  untracked,
} from "@angular/core";
import { toSignal } from "@angular/core/rxjs-interop";
import { ActivatedRoute } from "@angular/router";
import { EventApi } from "../../../core/event/event-api";
import { EventLiveStore } from "../../../core/event/event-live-store";
import { reloadOnRevisionChange } from "../../../core/event/revision-sync";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { ClueSection } from "./overrides/clue-section";
import { DangerZone } from "./overrides/danger-zone";
import { MoveSection } from "./overrides/move-section";
import { OverrideHistory } from "./overrides/override-history";
import { RoomExceptionSection } from "./overrides/room-exception-section";
import { SnapshotsSection } from "./overrides/snapshots-section";
import { SwapSection } from "./overrides/swap-section";
import { TeamStatusSection } from "./overrides/team-status-section";
import { RemoteData } from "./shared/remote-data";

/**
 * /event/admin/overrides: the organizer's manual fixes. `?team=<id>` preselects a team in every section and the
 * URL fragment (e.g. #swap) scrolls to one section; the Teams page's "…" menu links here that way.
 */
@Component({
  imports: [
    ClueSection,
    DangerZone,
    MoveSection,
    OverrideHistory,
    RoomExceptionSection,
    SnapshotsSection,
    SwapSection,
    TeamStatusSection,
    UiPageState,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<header class="event-page-header">
      <h1>Overrides</h1>
    </header>
    <ui-page-state
      [loading]="organization.loading() || board.loading()"
      loadingLabel="Loading teams and the event board…"
      [error]="organization.blockingError() ?? board.blockingError()"
      (retry)="reload()"
    />
    @if (organization.data(); as org) {
      @if (board.data(); as data) {
        <div class="event-stack">
          <p class="event-muted">
            Every override needs a reason, is recorded in the audit log and
            takes effect immediately on all screens.
          </p>
          <app-override-history
            [flags]="data.teamFlags"
            [recent]="data.recentOverrides"
            [rooms]="org.rooms"
          />
          <app-swap-section [teams]="org.teams" [rooms]="org.rooms" [preselect]="preselect()" (changed)="reload()" />
          <app-move-section [teams]="org.teams" [rooms]="org.rooms" [preselect]="preselect()" (changed)="reload()" />
          <app-room-exception-section
            [teams]="org.teams"
            [rooms]="org.rooms"
            [flags]="data.teamFlags"
            [roundRunning]="roundRunning()"
            [preselect]="preselect()"
            (changed)="reload()"
          />
          <app-clue-section [teams]="org.teams" [rooms]="org.rooms" [preselect]="preselect()" (changed)="reload()" />
          <app-team-status-section [teams]="org.teams" [preselect]="preselect()" (changed)="reload()" />
          <app-snapshots-section [revision]="data.state.revision" (changed)="reload()" />
          <app-danger-zone [teams]="org.teams" [preselect]="preselect()" (changed)="reload()" />
        </div>
      }
    }`,
})
export class EventAdminOverridesPage {
  private readonly eventApi = inject(EventApi);
  private readonly route = inject(ActivatedRoute);
  private readonly injector = inject(Injector);

  protected readonly organization = new RemoteData(() => this.eventApi.organization());
  protected readonly board = new RemoteData(() => this.eventApi.adminBoard());
  private readonly query = toSignal(this.route.queryParamMap);
  protected readonly preselect = computed(() => {
    const team = Number(this.query()?.get("team"));
    return Number.isInteger(team) && team > 0 ? team : null;
  });
  protected readonly roundRunning = computed(() => {
    const round = this.board.data()?.state.currentRound ?? 0;
    return round >= 1 && round <= 4;
  });

  constructor() {
    const live = inject(EventLiveStore);
    effect(() => {
      const organization = this.organization.data();
      if (organization) untracked(() => live.publishOrganization(organization));
    });
    effect(() => {
      const board = this.board.data();
      if (board) untracked(() => live.publishAdminBoard(board));
    });
    reloadOnRevisionChange(() => this.reload());
    void this.loadAndScroll();
  }

  protected reload(): void {
    void this.organization.load();
    void this.board.load();
  }

  private async loadAndScroll(): Promise<void> {
    await Promise.all([this.organization.load(), this.board.load()]);
    const fragment = this.route.snapshot.fragment;
    if (!fragment) return;
    afterNextRender(() => document.getElementById(fragment)?.scrollIntoView({ block: "start" }), {
      injector: this.injector,
    });
  }
}
