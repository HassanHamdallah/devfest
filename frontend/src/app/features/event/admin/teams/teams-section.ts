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
import { Organization, TeamView } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { TeamForm } from "./team-form";

/** Teams table (`data-team="<name>"` rows) with one create/edit form above it. */
@Component({
  selector: "app-teams-section",
  imports: [TeamForm, UiButton, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section class="event-stack" aria-labelledby="teams-heading">
    <h2 id="teams-heading">Teams</h2>
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
                <ui-button variant="secondary" size="sm" (click)="edit(team)"
                  >Edit</ui-button
                >
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="5" class="event-muted">No teams yet.</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  </section>`,
  styles: `
    h2 {
      margin: 0;
    }
    .event-stack {
      gap: var(--space-4);
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

  private readonly injector = inject(Injector);
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

  protected stopEditing(): void {
    this.editingId.set(null);
    this.changed.emit();
  }
}
