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
import {
  nextRoundBlockReason,
  nextRoundLabel,
  openFinalBlockReason,
} from "./control-view";

/** Header actions: "Next round" (absolute current+1, so a double click cannot skip) and the final. */
@Component({
  selector: "app-control-bar",
  imports: [UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="event-page-header__actions">
      <ui-button
        [disabled]="!!nextRoundReason() || busy()"
        (click)="nextRound()"
        >Next round</ui-button
      >
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
    <p class="control-bar__reasons event-muted">
      <span>{{ nextRoundReason() ?? nextRoundHint() }}</span>
      @if (openFinalReason(); as reason) {
        <span>{{ reason }}</span>
      }
    </p>`,
  styles: `
    :host {
      display: grid;
      justify-items: end;
      gap: var(--space-1);
    }
    .control-bar__reasons {
      display: flex;
      flex-wrap: wrap;
      gap: 0 var(--space-3);
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

  protected readonly nextRoundReason = computed(() =>
    nextRoundBlockReason(this.state()),
  );
  protected readonly nextRoundHint = computed(() =>
    nextRoundLabel(this.state()),
  );
  protected readonly openFinalReason = computed(() =>
    openFinalBlockReason(this.state()),
  );

  protected nextRound(): void {
    if (this.nextRoundReason() || this.busy()) return;
    this.action.emit({
      kind: "setRound",
      round: this.state().currentRound + 1,
    });
  }

  protected openFinal(): void {
    if (!this.openFinalReason() && !this.busy())
      this.action.emit({ kind: "openFinal" });
  }

  protected closeFinal(): void {
    if (!this.busy()) this.action.emit({ kind: "closeFinal" });
  }
}
