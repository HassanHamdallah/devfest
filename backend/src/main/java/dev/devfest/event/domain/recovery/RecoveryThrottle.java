package dev.devfest.event.domain.recovery;

import java.time.Duration;
import java.time.Instant;

/**
 * How often a team may check its recovery work: one ML stage check every 2 seconds, one Arduino run every 3 seconds
 * (per team, whatever the stage). A check refused for this reason is not recorded. Pure.
 */
public final class RecoveryThrottle {

  private RecoveryThrottle() {}

  public static Duration interval(RecoveryLevel level) {
    return level == RecoveryLevel.ML ? Duration.ofSeconds(2) : Duration.ofSeconds(3);
  }

  /** Whole seconds (rounded up) before the next check of {@code level} is accepted; 0 when it may run now. */
  public static int waitSeconds(RecoveryLevel level, Instant lastCheckAt, Instant now) {
    if (lastCheckAt == null) return 0;
    long millis = Duration.between(now, lastCheckAt.plus(interval(level))).toMillis();
    return millis <= 0 ? 0 : (int) ((millis + 999) / 1000);
  }
}
