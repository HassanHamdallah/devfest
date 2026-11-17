import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, linkedSignal, signal } from "@angular/core";
import { formatMinutesSeconds, remainingSeconds } from "../../../core/event/countdown";
import { RoundClock } from "../../../core/event/event-models";

/**
 * A plain "mm:ss" countdown for the final's decryption phase (with " left" when asked), counted down locally once a
 * second and restarted from the server's value whenever a new clock arrives (every board refresh). A paused final shows
 * the frozen value with "· paused". It renders nothing without a clock. The clock's phase is the final timer's phase and
 * its remainingSeconds the seconds until the investigation opens.
 */
@Component({
  selector: "ui-phase-clock",
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@if (text(); as value) {<span data-phase-clock>{{ value }}</span>}`,
})
export class UiPhaseClock {
  readonly clock = input<RoundClock | null>(null);
  /** Adds "left" after a running value ("04:12 left"). */
  readonly left = input(false);

  private readonly now = signal(Date.now());
  /** When the current clock arrived; resets with every new one. */
  private readonly receivedAt = linkedSignal({ source: this.clock, computation: () => Date.now() });
  protected readonly text = computed(() => {
    const clock = this.clock();
    if (!clock) return "";
    const seconds = remainingSeconds(clock, this.receivedAt(), this.now()) ?? 0;
    return formatMinutesSeconds(seconds) + (clock.phase === "PAUSED" ? " · paused" : this.left() ? " left" : "");
  });

  constructor() {
    const tick = () => this.now.set(Date.now());
    const timer = setInterval(tick, 1000);
    document.addEventListener("visibilitychange", tick);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", tick);
    });
  }
}
