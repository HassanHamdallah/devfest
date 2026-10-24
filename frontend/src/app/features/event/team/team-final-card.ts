import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from "@angular/core";
import { RouterLink } from "@angular/router";
import { TeamBoard } from "../../../core/event/event-models";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";

/** Entry to the final: a link while it is open (or after the team solved the case), otherwise why it is locked. */
@Component({
  selector: "event-team-final-card",
  imports: [RouterLink, UiCard, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .open-final {
      display: inline-flex;
      align-items: center;
      min-height: 44px;
      padding: 0 var(--space-4);
      font-weight: var(--font-weight-semibold);
      color: var(--color-on-primary);
      background: var(--color-primary);
      border-radius: var(--radius-md);
    }
    .open-final:hover {
      text-decoration: none;
      background: var(--color-primary-hover);
    }
  `,
  template: `<ui-card>
    <h2 ui-card-header>Final</h2>
    @if (canOpen()) {
      <p>{{ openText() }}</p>
      <a class="open-final" routerLink="/event/team/final">Open final</a>
    } @else {
      <div class="event-inline">
        <ui-status-chip variant="warning">Final locked</ui-status-chip>
      </div>
      <p class="event-muted">{{ lockedReason() }}</p>
    }
  </ui-card>`,
})
export class EventTeamFinalCard {
  readonly board = input.required<TeamBoard>();

  protected readonly canOpen = computed(
    () => this.board().finalOpen || this.board().finalStage === "FINISHED",
  );
  protected readonly openText = computed(() =>
    this.board().finalStage === "FINISHED"
      ? "Your team solved the case."
      : "The final is open for all teams.",
  );
  protected readonly lockedReason = computed(() =>
    this.board().finalStage === "CLOSED"
      ? "The final is closed."
      : "The final opens for all teams at the same time.",
  );
}
