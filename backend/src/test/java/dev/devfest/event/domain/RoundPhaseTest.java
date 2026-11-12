package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoundPhaseTest {

  @Test
  void theContestLinkIsOpenOnlyWhileTheTimerRunsOrIsPaused() {
    assertFalse(RoundPhase.ASSIGNED.contestLinkOpen(), "before the timer starts");
    assertTrue(RoundPhase.RUNNING.contestLinkOpen());
    assertTrue(RoundPhase.PAUSED.contestLinkOpen());
    assertFalse(RoundPhase.TIME_UP.contestLinkOpen(), "after the round ends");
  }

  @Test
  void everyPhaseIsDecidedExplicitly() {
    // A new phase must be added to the test above on purpose, not become a link-visible phase by accident.
    assertEquals(4, RoundPhase.values().length);
  }
}
