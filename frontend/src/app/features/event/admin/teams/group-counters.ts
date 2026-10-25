import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from "@angular/core";
import { Organization } from "../../../../core/event/event-models";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { PlannedTeamsForm } from "./planned-teams-form";
import { groupCounters } from "./teams-view";

/**
 * The planned team count, active teams per group ("3 / 4", max = ceil(planned / 4)) and the server's
 * balance warnings (team count, over-full groups, sizes differing by more than one).
 */
@Component({
  selector: "app-group-counters",
  imports: [PlannedTeamsForm, UiAlert, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section aria-labelledby="groups-heading">
    <h2 id="groups-heading">Groups</h2>
    <app-planned-teams-form
      [organization]="organization()"
      (changed)="changed.emit()"
    />
    <ul class="group-counters" aria-label="Active teams per group">
      @for (group of counters(); track group.groupCode) {
        <li
          class="group-counter"
          role="group"
          [attr.aria-label]="'Group ' + group.groupCode"
          [attr.data-group]="group.groupCode"
          [class.group-counter--warning]="group.overLimit"
        >
          <span class="group-counter__label">Group {{ group.groupCode }}</span>
          <strong class="group-counter__value">{{ group.text }}</strong>
          <ui-status-chip [variant]="group.variant">{{
            group.hint
          }}</ui-status-chip>
        </li>
      }
    </ul>
    @if (organization().warnings.length) {
      <ui-alert variant="warning" class="group-warnings">
        <ul>
          @for (warning of organization().warnings; track warning.message) {
            <li [attr.data-warning]="warning.code">{{ warning.message }}</li>
          }
        </ul>
      </ui-alert>
    }
  </section>`,
  styles: `
    h2 {
      margin: 0 0 var(--space-3);
    }
    .group-counters {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .group-counter {
      display: inline-flex;
      align-items: center;
      gap: var(--space-2);
      min-block-size: 44px;
      padding: var(--space-1) var(--space-2) var(--space-1) var(--space-4);
      background: var(--color-surface);
      border: var(--border-outline-thin);
      border-radius: var(--radius-full);
    }
    .group-counter--warning {
      background: var(--color-error-subtle);
    }
    .group-counter__label {
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-semibold);
    }
    .group-counter__value {
      font-size: var(--font-size-lg);
      font-variant-numeric: tabular-nums;
    }
    app-planned-teams-form {
      display: block;
      margin-bottom: var(--space-4);
    }
    .group-warnings {
      display: block;
      margin-top: var(--space-4);
    }
    .group-warnings ul {
      margin: 0;
      padding-left: var(--space-5);
    }
  `,
})
export class GroupCounters {
  readonly organization = input.required<Organization>();
  readonly changed = output<void>();

  protected readonly counters = computed(() =>
    groupCounters(this.organization()),
  );
}
