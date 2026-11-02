import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  linkedSignal,
  signal,
} from "@angular/core";
import { RoundClock } from "../../../core/event/event-models";
import {
  COUNTDOWN_WARNING_SECONDS,
  countdownLabel,
  effectivePhase,
  formatMinutesSeconds,
  remainingSeconds,
} from "../../../core/event/countdown";

/**
 * Round countdown from the last fetched clock (renders nothing in rounds 0 and 5). It ticks locally once a
 * second, restarts from the server's value whenever a new clock arrives (each board refetch), and catches up at
 * once when the tab becomes visible again. It never polls; the server decides when time is up.
 * `large` shows a big "mm:ss" for the admin.
 */
@Component({
  selector: "ui-countdown",
  template: `@if (large()) {
      <span class="ui-countdown__digits">{{ digits() }}</span>
    } @else {
      {{ label() }}
    }`,
  styleUrl: "./ui-countdown.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    role: "timer",
    "[attr.aria-label]": "label()",
    "[hidden]": "!label()",
    "[class.ui-countdown-host--large]": "large()",
    "[class.ui-countdown-host--warning]": "warning()",
    "[class.ui-countdown-host--expired]": "expired()",
  },
})
export class UiCountdown {
  /** Any RoundClock-shaped value (RoundClock, EventStateView). */
  readonly clock = input<RoundClock | null>(null);
  readonly large = input(false);

  private readonly now = signal(Date.now());
  /** Local time when the current clock value arrived; resets with every new clock. */
  private readonly receivedAt = linkedSignal({
    source: this.clock,
    computation: () => Date.now(),
  });

  private readonly seconds = computed(() => {
    const clock = this.clock();
    return clock ? remainingSeconds(clock, this.receivedAt(), this.now()) : null;
  });
  private readonly phase = computed(() => {
    const clock = this.clock();
    return clock ? effectivePhase(clock, this.seconds()) : null;
  });

  protected readonly label = computed(() =>
    countdownLabel(this.phase(), this.seconds()),
  );
  protected readonly digits = computed(() =>
    formatMinutesSeconds(this.seconds() ?? 0),
  );
  protected readonly expired = computed(() => this.phase() === "TIME_UP");
  protected readonly warning = computed(() => {
    const seconds = this.seconds();
    return (
      this.phase() !== "ASSIGNED" &&
      !this.expired() &&
      seconds !== null &&
      seconds < COUNTDOWN_WARNING_SECONDS
    );
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
