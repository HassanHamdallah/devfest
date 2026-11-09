package dev.devfest.event.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The wait after repeated wrong answers (unlock, and each layer on its own): every fifth wrong answer in a row starts a
 * 30-second wait counted from that answer; a correct answer ends the streak. Answers refused during a wait are not
 * evaluated, so they are not attempts and do not extend the streak. Pure.
 */
public final class AttemptThrottle {

  public static final int WRONG_IN_A_ROW = 5;
  public static final Duration WAIT = Duration.ofSeconds(30);

  private AttemptThrottle() {}

  /**
   * @param wrongInARow wrong answers since the last correct one (0 when none)
   * @param lastAttemptAt when the latest attempt was evaluated; null when there is none
   * @return the whole seconds still to wait (rounded up), 0 when the team may answer now
   */
  public static int waitSeconds(int wrongInARow, Instant lastAttemptAt, Instant now) {
    if (wrongInARow <= 0 || wrongInARow % WRONG_IN_A_ROW != 0 || lastAttemptAt == null) return 0;
    long millis = Duration.between(now, lastAttemptAt.plus(WAIT)).toMillis();
    return millis <= 0 ? 0 : (int) ((millis + 999) / 1000);
  }
}
