import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  signal,
} from "@angular/core";
import { RoundClock } from "../../../core/event/event-models";
import {
  clockOffsetMs,
  countdownLabel,
  remainingSeconds,
} from "../../../core/event/countdown";

/**
 * Round countdown ("12:34 left" / "Time is up"; renders nothing when no round runs). Ticks once a second
 * locally from the last fetched clock; it never polls and nothing closes when it reaches zero.
 */
@Component({
  selector: "ui-countdown",
  template: `{{ label() }}`,
  styleUrl: "./ui-countdown.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    role: "timer",
    "[hidden]": "!label()",
    "[class.ui-countdown-host--expired]": "expired()",
  },
})
export class UiCountdown {
  /** Any RoundClock-shaped value (RoundClock, EventStateView). */
  readonly clock = input<RoundClock | null>(null);
  /** Date.now() when the clock was fetched; defaults to the moment the input changed. */
  readonly fetchedAt = input<number | null>(null);

  private readonly now = signal(Date.now());

  private readonly offsetMs = computed(() => {
    const clock = this.clock();
    return clock
      ? clockOffsetMs(clock.serverNow, this.fetchedAt() ?? Date.now())
      : 0;
  });

  private readonly seconds = computed(() => {
    const clock = this.clock();
    return clock ? remainingSeconds(clock, this.offsetMs(), this.now()) : null;
  });

  protected readonly label = computed(() => countdownLabel(this.seconds()));
  protected readonly expired = computed(() => this.seconds() === 0);

  constructor() {
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }
}
