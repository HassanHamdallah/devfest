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
import { EventApi } from "../../../../core/event/event-api";
import { Organization, TeamView } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { ActionState } from "../shared/action-state";
import { ActionStatus } from "../shared/action-status";
import { GenerateTeamsForm } from "./generate-teams-form";
import { TeamForm } from "./team-form";
import { lastSeenLabel } from "./teams-view";

/**
 * Teams table (`data-team="<name>"` rows) with bulk generation and one create/edit form above it. Each
 * row shows whether the team's device is signed in and can sign it out.
 */
@Component({
  selector: "app-teams-section",
  imports: [
    ActionStatus,
    GenerateTeamsForm,
    TeamForm,
    UiButton,
    UiConfirmDialog,
    UiStatusChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section class="event-stack" aria-labelledby="teams-heading">
    <h2 id="teams-heading">Teams</h2>
    <app-generate-teams-form
      [organization]="organization()"
      (changed)="changed.emit()"
    />
    <app-team-form
      [organization]="organization()"
      [team]="editingTeam()"
      (saved)="stopEditing()"
      (cancelled)="stopEditing()"
    />
    <div class="event-table-wrap">
      <table>
        <caption>
          Teams
        </caption>
        <thead>
          <tr>
            <th scope="col">Team</th>
            <th scope="col">Group</th>
            <th scope="col">Member account</th>
            <th scope="col">Status</th>
            <th scope="col">Device</th>
            <th scope="col">Actions</th>
          </tr>
        </thead>
        <tbody>
          @for (team of organization().teams; track team.id) {
            <tr [attr.data-team]="team.name">
              <th scope="row">{{ team.name }}</th>
              <td>{{ team.groupCode }}</td>
              <td>{{ team.accountEmail }}</td>
              <td>
                @if (team.active) {
                  <ui-status-chip variant="success">Active</ui-status-chip>
                } @else {
                  <ui-status-chip variant="warning">Inactive</ui-status-chip>
                }
              </td>
              <td>
                <div class="teams-section__device">
                  @if (team.signedIn) {
                    <ui-status-chip variant="success">Signed in</ui-status-chip>
                  } @else {
                    <ui-status-chip>Not signed in</ui-status-chip>
                  }
                  @if (lastSeen(team); as seen) {
                    <span class="event-muted">last seen {{ seen }}</span>
                  }
                </div>
              </td>
              <td>
                <div class="event-inline">
                  <ui-button variant="secondary" size="sm" (click)="edit(team)"
                    >Edit</ui-button
                  >
                  <ui-button
                    variant="ghost"
                    size="sm"
                    [disabled]="signOut.busy()"
                    (click)="signingOut.set(team)"
                    >Sign out team</ui-button
                  >
                </div>
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="6" class="event-muted">No teams yet.</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    <app-action-status [state]="signOut" />
    <ui-confirm-dialog
      [open]="signingOut() !== null"
      [title]="'Sign out ' + (signingOut()?.name ?? '') + '?'"
      message="The team's device is signed out at its next request and shows that an organizer signed it out. The team can sign in again."
      confirmVariant="danger"
      [busy]="signOut.busy()"
      (confirmed)="confirmSignOut()"
      (cancelled)="closeSignOut()"
    />
  </section>`,
  styles: `
    h2 {
      margin: 0;
    }
    .event-stack {
      gap: var(--space-4);
    }
    .teams-section__device {
      display: grid;
      justify-items: start;
      gap: var(--space-1);
      font-size: var(--font-size-sm);
    }
    td:last-child {
      white-space: nowrap;
    }
    th[scope="row"] {
      font-size: inherit;
      letter-spacing: normal;
      color: var(--color-text);
      background: none;
    }
  `,
})
export class TeamsSection {
  readonly organization = input.required<Organization>();
  readonly changed = output<void>();

  private readonly eventApi = inject(EventApi);
  private readonly injector = inject(Injector);
  protected readonly signOut = new ActionState();
  /** The team whose sign-out awaits confirmation. */
  protected readonly signingOut = signal<TeamView | null>(null);
  private readonly formRef = viewChild.required(TeamForm, { read: ElementRef });
  private readonly editingId = signal<number | null>(null);
  protected readonly editingTeam = computed(() => {
    const id = this.editingId();
    return this.organization().teams.find((team) => team.id === id) ?? null;
  });

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
