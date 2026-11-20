package dev.devfest.event.domain.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** One ML check every 2 seconds, one Arduino run every 3 seconds, per team. */
class RecoveryThrottleTest {

  private static final Instant LAST = Instant.parse("2026-11-20T18:10:00Z");

  @Test
  void anMlCheckWaitsTwoSecondsAfterTheLastOne() {
    assertEquals(0, RecoveryThrottle.waitSeconds(RecoveryLevel.ML, null, LAST));
    assertEquals(2, RecoveryThrottle.waitSeconds(RecoveryLevel.ML, LAST, LAST));
    assertEquals(1, RecoveryThrottle.waitSeconds(RecoveryLevel.ML, LAST, LAST.plusMillis(1001)));
    assertEquals(1, RecoveryThrottle.waitSeconds(RecoveryLevel.ML, LAST, LAST.plusMillis(1999)));
    assertEquals(0, RecoveryThrottle.waitSeconds(RecoveryLevel.ML, LAST, LAST.plusSeconds(2)));
  }

  @Test
  void anArduinoRunWaitsThreeSecondsAfterTheLastOne() {
    assertEquals(3, RecoveryThrottle.waitSeconds(RecoveryLevel.ARDUINO, LAST, LAST));
    assertEquals(1, RecoveryThrottle.waitSeconds(RecoveryLevel.ARDUINO, LAST, LAST.plusMillis(2500)));
    assertEquals(0, RecoveryThrottle.waitSeconds(RecoveryLevel.ARDUINO, LAST, LAST.plusSeconds(3)));
  }
}
