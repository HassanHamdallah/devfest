import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  untracked,
} from "@angular/core";
import { DatePipe } from "@angular/common";
import { RouterLink } from "@angular/router";
import { UiDrawer } from "../../shared/ui/ui-drawer/ui-drawer";
import { UiRoomChip } from "../../shared/ui/ui-room-chip/ui-room-chip";
import { UiStatusChip } from "../../shared/ui/ui-status-chip/ui-status-chip";
import { EventApi } from "../event/event-api";
import { EventLiveStore } from "../event/event-live-store";
import { formatPoints } from "../event/format";
import { TeamDrawerDeductions } from "./team-drawer-deductions";
import { TEAM_OVERRIDE_LINKS, TeamDrawerService } from "./team-drawer.service";
import { teamSummary } from "./team-summary";

/**
 * The organizer's side panel for one team: where it is this round, clues, score, hints and cheating, device, flags, and the
 * same override actions as the Teams page "…" menu. It reads the boards pages already loaded and fetches
 * one (existing endpoint) only if none is loaded yet.
 */
@Component({
  selector: "app-team-drawer",
  imports: [DatePipe, RouterLink, TeamDrawerDeductions, UiDrawer, UiRoomChip, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: "./team-drawer.html",
  styleUrl: "./team-drawer.css",
})
export class TeamDrawer {
  protected readonly drawer = inject(TeamDrawerService);
  private readonly store = inject(EventLiveStore);
  private readonly eventApi = inject(EventApi);

  protected readonly links = TEAM_OVERRIDE_LINKS;
  protected readonly summary = computed(() => {
    const id = this.drawer.teamId();
    const organization = this.store.organization();
    if (id === null || !organization) return null;
    return teamSummary(id, organization, this.store.adminBoard());
  });
  protected readonly heading = computed(
    () => this.summary()?.team.name ?? "Team",
  );
  /** "42.5 of 100" from the team's breakdown, or a dash while the board is loading. */
  protected readonly scoreText = computed(() => {
    const scores = this.summary()?.scores;
    return scores ? `${formatPoints(scores.total)} of 100` : "—";
  });

  constructor() {
    effect(() => {
      if (this.drawer.teamId() === null) return;
      untracked(() => void this.loadMissing());
    });
  }

  private async loadMissing(): Promise<void> {
    try {
      if (!this.store.organization())
        this.store.publishOrganization(await this.eventApi.organization());
      if (!this.store.adminBoard())
        this.store.publishAdminBoard(await this.eventApi.adminBoard());
    } catch {
      // The drawer shows what it has; the pages themselves report load errors.
    }
  }
}
