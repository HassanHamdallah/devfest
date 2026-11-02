import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from "@angular/core";
import { Organization } from "../../../../core/event/event-models";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { groupCounters } from "./teams-view";

/** Active teams per group ("3 / 4"); anything but 4 is flagged because the rotation needs full groups. */
@Component({
  selector: "app-group-counters",
  imports: [UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section aria-labelledby="groups-heading">
    <h2 id="groups-heading">Groups</h2>
    <div class="event-grid event-grid--metrics">
      @for (group of counters(); track group.groupCode) {
        <div
          class="group-counter"
          role="group"
          [attr.aria-label]="'Group ' + group.groupCode"
          [attr.data-group]="group.groupCode"
          [class.group-counter--warning]="!group.complete"
        >
          <span class="group-counter__label">Group {{ group.groupCode }}</span>
          <strong class="group-counter__value">{{ group.text }}</strong>
          <ui-status-chip [variant]="group.variant">{{
            group.hint
          }}</ui-status-chip>
        </div>
      }
    </div>
  </section>`,
  styles: `
    h2 {
      margin: 0 0 var(--space-3);
    }
    .group-counter {
      display: grid;
      justify-items: start;
      gap: var(--space-1);
      padding: var(--space-4);
      background: var(--color-surface);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-card);
    }
    .group-counter--warning {
      border-color: color-mix(in srgb, var(--color-warning) 45%, transparent);
    }
    .group-counter__label {
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    .group-counter__value {
      font-size: var(--font-size-xl);
    }
  `,
})
export class GroupCounters {
  readonly organization = input.required<Organization>();

  protected readonly counters = computed(() =>
    groupCounters(this.organization()),
  );
}
