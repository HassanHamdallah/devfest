package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The phase rule has no clock: a team is in DECRYPTION until it has decrypted all four evidence files (the active
 * second its fourth passphrase was accepted is stored once) and in INVESTIGATION from then on, however late that is.
 */
class FinalPhaseTest {

  @Test
  void withoutAllFourFilesTheTeamStaysInPhaseOne() {
    assertEquals(FinalPhase.DECRYPTION, FinalPhase.of(null));
  }

  @Test
  void theFourthFileOpensTheInvestigationWhateverTheClockSays() {
    assertEquals(FinalPhase.INVESTIGATION, FinalPhase.of(0));
    assertEquals(FinalPhase.INVESTIGATION, FinalPhase.of(360), "6:00");
    assertEquals(FinalPhase.INVESTIGATION, FinalPhase.of(480), "8:00");
    assertEquals(FinalPhase.INVESTIGATION, FinalPhase.of(1800), "30:00, long after the 15-minute estimate");
    assertEquals(FinalPhase.INVESTIGATION, FinalPhase.of(2399));
  }

  @Test
  void theEstimateIsOnlyAnEstimate() {
    assertEquals(15, FinalPhase.DEFAULT_PHASE1_MINUTES);
    assertEquals(1, FinalPhase.MIN_PHASE1_MINUTES);
    assertEquals(39, FinalPhase.MAX_PHASE1_MINUTES);
  }
}
