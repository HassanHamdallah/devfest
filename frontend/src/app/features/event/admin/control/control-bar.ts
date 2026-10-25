import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from "@angular/core";
import { EventStateView } from "../../../../core/event/event-models";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { ControlAction } from "./control-actions";
import { openFinalBlockReason } from "./control-view";

/** Header action for the final: "Open final" (once) or "Close final"; kept away from the round buttons. */
@Component({
  selector: "app-control-bar",
  imports: [UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="event-page-header__actions">
      @if (state().finalOpen) {
        <ui-button variant="danger" [disabled]="busy()" (click)="closeFinal()"
          >Close final</ui-button
        >
      } @else {
        <ui-button
          variant="secondary"
          [disabled]="!!openFinalReason() || busy()"
          (click)="openFinal()"
          >Open final</ui-button
        >
      }
    </div>
    @if (openFinalReason(); as reason) {
      <p class="control-bar__reasons event-muted">{{ reason }}</p>
    }`,
  styles: `
    :host {
      display: grid;
      justify-items: end;
      gap: var(--space-1);
    }
    .control-bar__reasons {
      margin: 0;
      font-size: var(--font-size-sm);
    }
    @media (max-width: 800px) {
      :host {
        justify-items: start;
      }
    }
  `,
})
export class ControlBar {
  readonly state = input.required<EventStateView>();
  readonly busy = input(false);
  readonly action = output<ControlAction>();

  protected readonly openFinalReason = computed(() =>
    openFinalBlockReason(this.state()),
  );

  protected openFinal(): void {
    if (!this.openFinalReason() && !this.busy())
      this.action.emit({ kind: "openFinal" });
  }

  protected closeFinal(): void {
    if (!this.busy()) this.action.emit({ kind: "closeFinal" });
  }
}
