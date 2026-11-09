package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Five wrong answers in a row start a 30-second wait; nothing earlier does. */
class AttemptThrottleTest {

  private static final Instant T = Instant.parse("2026-01-01T10:00:00Z");

  @Test
  void noWaitBeforeTheFifthWrongAnswer() {
    for (int wrong = 0; wrong <= 4; wrong++) assertEquals(0, AttemptThrottle.waitSeconds(wrong, T, T), "wrong " + wrong);
  }

  @Test
  void theFifthWrongAnswerStartsAThirtySecondWait() {
    assertEquals(30, AttemptThrottle.waitSeconds(5, T, T));
    assertEquals(20, AttemptThrottle.waitSeconds(5, T, T.plusSeconds(10)));
    assertEquals(1, AttemptThrottle.waitSeconds(5, T, T.plusMillis(29_001)));
    assertEquals(0, AttemptThrottle.waitSeconds(5, T, T.plusSeconds(30)));
  }

  @Test
  void everyFifthWrongAnswerStartsAnotherWait() {
    assertEquals(30, AttemptThrottle.waitSeconds(10, T, T));
    assertEquals(0, AttemptThrottle.waitSeconds(6, T, T));
  }

  @Test
  void noAttemptMeansNoWait() {
    assertEquals(0, AttemptThrottle.waitSeconds(5, null, T));
  }
}
