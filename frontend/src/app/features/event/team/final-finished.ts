import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from "@angular/core";
import { RouterLink } from "@angular/router";
import { TeamFinal, TeamFinish } from "../../../core/event/event-models";
import {
  formatAdjustedSeconds,
  formatDateTime,
  formatPenaltyMinutes,
} from "../../../core/event/format";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";

/** FINISHED stage: the team's result; the ranking link appears once the organizers publish results. */
@Component({
  selector: "event-final-finished",
  imports: [RouterLink, UiCard, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    dl {
      display: grid;
      grid-template-columns: max-content minmax(0, 1fr);
      gap: var(--space-2) var(--space-4);
      margin: 0 0 var(--space-4);
    }
    dt {
      color: var(--color-text-muted);
    }
    dd {
      margin: 0;
      overflow-wrap: anywhere;
    }
  `,
  template: `<ui-card>
    <div class="event-inline" ui-card-header>
      <h2>Case solved</h2>
      <ui-status-chip variant="success">Finished</ui-status-chip>
    </div>
    @if (finish(); as result) {
      <dl>
        <dt>Accused suspect</dt>
        <dd>{{ result.suspectName }}</dd>
        <dt>Finish time</dt>
        <dd>{{ finishedAt() }}</dd>
        <dt>Penalties</dt>
        <dd>{{ penalties() }}</dd>
        <dt>Adjusted time</dt>
        <dd>{{ adjustedTime() }}</dd>
        <dt>Receipt ID</dt>
        <dd>
          <code>{{ result.receiptId }}</code>
        </dd>
      </dl>
    }
    @if (final().resultsPublished) {
      <a routerLink="/event/results">View ranking</a>
    } @else {
      <p class="event-muted">
        The ranking appears here once the organizers publish the results.
      </p>
    }
  </ui-card>`,
})
export class EventFinalFinished {
  readonly final = input.required<TeamFinal>();

  protected readonly finish = computed<TeamFinish | null>(
    () => this.final().finish,
  );
  protected readonly finishedAt = computed(() =>
    formatDateTime(this.finish()?.finishedAt ?? null),
  );
  protected readonly penalties = computed(() =>
    formatPenaltyMinutes(this.final().penaltyMinutes),
  );
  protected readonly adjustedTime = computed(() =>
    formatAdjustedSeconds(this.finish()?.adjustedSeconds ?? null),
  );
}
