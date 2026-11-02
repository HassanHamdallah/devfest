import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  untracked,
} from "@angular/core";
import { EventApi } from "../../../core/event/event-api";
import { EventLiveStore } from "../../../core/event/event-live-store";
import { reloadOnRevisionChange } from "../../../core/event/revision-sync";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { RemoteData } from "./shared/remote-data";
import { AccountForm } from "./teams/account-form";
import { AccountsTable } from "./teams/accounts-table";
import { GroupCounters } from "./teams/group-counters";
import { ManagerAssignment } from "./teams/manager-assignment";
import { TeamsSection } from "./teams/teams-section";

/** Event setup from GET /admin/organization: planned teams and groups, room managers, accounts and teams. */
@Component({
  imports: [
    AccountForm,
    AccountsTable,
    GroupCounters,
    ManagerAssignment,
    TeamsSection,
    UiAlert,
    UiPageState,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<header class="event-page-header">
      <h1>Teams &amp; accounts</h1>
    </header>
    <ui-page-state
      [loading]="organization.loading()"
      loadingLabel="Loading teams and accounts…"
      [error]="organization.blockingError()"
      (retry)="organization.retry()"
    />
    @if (organization.data(); as org) {
      <div class="event-stack">
        @if (organization.staleError(); as error) {
          <ui-alert variant="warning"
            >Showing the last loaded data. {{ error }}</ui-alert
          >
        }
        <app-group-counters [organization]="org" (changed)="reload()" />
        <section aria-labelledby="managers-heading">
          <h2 id="managers-heading">Room managers</h2>
          <div class="event-grid">
            @for (room of org.rooms; track room.id) {
              <app-manager-assignment
                [room]="room"
                [organization]="org"
                (changed)="reload()"
              />
            }
          </div>
        </section>
        <section
          class="event-stack teams-page__accounts"
          aria-labelledby="accounts-heading"
        >
          <h2 id="accounts-heading">Accounts</h2>
          <app-account-form (changed)="reload()" />
          <app-accounts-table [organization]="org" (changed)="reload()" />
        </section>
        <app-teams-section [organization]="org" (changed)="reload()" />
      </div>
    }`,
  styles: `
    h2 {
      margin: 0 0 var(--space-3);
    }
    .teams-page__accounts {
      gap: var(--space-4);
    }
    .teams-page__accounts h2 {
      margin: 0;
    }
  `,
})
export class EventAdminTeamsPage {
  private readonly eventApi = inject(EventApi);
  protected readonly organization = new RemoteData(() =>
    this.eventApi.organization(),
  );

  constructor() {
    const live = inject(EventLiveStore);
    effect(() => {
      const organization = this.organization.data();
      if (organization) untracked(() => live.publishOrganization(organization));
    });
    reloadOnRevisionChange(() => this.reload());
    this.reload();
  }

  protected reload(): void {
    void this.organization.load();
  }
}
