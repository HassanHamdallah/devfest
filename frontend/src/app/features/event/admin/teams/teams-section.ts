import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { RouterLink } from "@angular/router";
import { EventApi } from "../../../../core/event/event-api";
import { Organization, TeamView } from "../../../../core/event/event-models";
import { NavIcon } from "../../../../core/shell/nav-icon";
import {
  TEAM_OVERRIDE_LINKS,
  TeamDrawerService,
} from "../../../../core/shell/team-drawer.service";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiInput } from "../../../../shared/ui/ui-input/ui-input";
import { UiSelect } from "../../../../shared/ui/ui-select/ui-select";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { ActionState } from "../shared/action-state";
import { ActionStatus } from "../shared/action-status";
import { GenerateTeamsForm } from "./generate-teams-form";
import { TeamForm } from "./team-form";
import {
  DEFAULT_TEAM_QUERY,
  DEVICE_FILTER_OPTIONS,
  GROUP_FILTER_OPTIONS,
  STATUS_FILTER_OPTIONS,
  TeamSortKey,
  TeamTableQuery,
  lastSeenLabel,
  visibleTeams,
} from "./teams-view";

/**
 * Teams table (`data-team="<name>"` rows) with search, filters and sortable columns, bulk generation in a
 * collapsible panel, and one create/edit form. Each row shows whether the team's device is signed in and can
 * sign it out; the team name opens the team drawer.
 */
@Component({
  selector: "app-teams-section",
  imports: [
    ActionStatus,
    FormsModule,
    GenerateTeamsForm,
    NavIcon,
    RouterLink,
    TeamForm,
    UiButton,
    UiConfirmDialog,
    UiInput,
    UiSelect,
    UiStatusChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: "./teams-section.html",
  styleUrl: "./teams-section.css",
})
export class TeamsSection {
  readonly organization = input.required<Organization>();
  readonly changed = output<void>();

  private readonly eventApi = inject(EventApi);
  private readonly injector = inject(Injector);
  protected readonly drawer = inject(TeamDrawerService);
  protected readonly signOut = new ActionState();
  /** The team whose sign-out awaits confirmation. */
  protected readonly signingOut = signal<TeamView | null>(null);
  private readonly formRef = viewChild.required(TeamForm, { read: ElementRef });
  private readonly editingId = signal<number | null>(null);
  protected readonly editingTeam = computed(() => {
    const id = this.editingId();
    return this.organization().teams.find((team) => team.id === id) ?? null;
  });

  protected readonly groupOptions = GROUP_FILTER_OPTIONS;
  protected readonly deviceOptions = DEVICE_FILTER_OPTIONS;
  protected readonly statusOptions = STATUS_FILTER_OPTIONS;
  protected readonly query = signal<TeamTableQuery>(DEFAULT_TEAM_QUERY);
  protected readonly rows = computed(() =>
    visibleTeams(this.organization().teams, this.query()),
  );
  protected readonly filtered = computed(
    () => this.rows().length !== this.organization().teams.length,
  );

  /** Entries of the row's "…" menu: the matching section of the Overrides page with this team preselected. */
  protected readonly menu = TEAM_OVERRIDE_LINKS;

  protected setQuery(change: Partial<TeamTableQuery>): void {
    this.query.update((query) => ({ ...query, ...change }));
  }

  protected clearFilters(): void {
    const { sort, descending } = this.query();
    this.query.set({ ...DEFAULT_TEAM_QUERY, sort, descending });
  }

  protected sortBy(sort: TeamSortKey): void {
    this.query.update((query) => ({
      ...query,
      sort,
      descending: query.sort === sort ? !query.descending : false,
    }));
  }

  protected ariaSort(key: TeamSortKey): "ascending" | "descending" | null {
    const { sort, descending } = this.query();
    if (sort !== key) return null;
    return descending ? "descending" : "ascending";
  }

  protected edit(team: TeamView): void {
    this.editingId.set(team.id);
    afterNextRender(
      () =>
        (this.formRef().nativeElement as HTMLElement).scrollIntoView({
          block: "start",
        }),
      { injector: this.injector },
    );
  }

  protected roomName(team: TeamView): string {
    return this.organization().rooms.find((room) => room.code === team.exceptionRoom)?.name ?? "";
  }

  protected lastSeen(team: TeamView): string | null {
    return lastSeenLabel(team.lastSeenAt);
  }

  protected closeSignOut(): void {
    if (!this.signOut.busy()) this.signingOut.set(null);
  }

  protected async confirmSignOut(): Promise<void> {
    const team = this.signingOut();
    if (!team) return;
    const ok = await this.signOut.run(
      () => this.eventApi.signOutTeam(team.id),
      `${team.name} was signed out.`,
    );
    this.signingOut.set(null);
    if (ok) this.changed.emit();
  }

  protected stopEditing(): void {
    this.editingId.set(null);
    this.changed.emit();
  }
}
