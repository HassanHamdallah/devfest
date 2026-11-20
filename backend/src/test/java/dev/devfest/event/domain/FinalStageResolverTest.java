package dev.devfest.event.domain;

import static dev.devfest.event.domain.FinalStageResolver.resolve;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Every team plays: K = 4 goes to the case, K = 0..3 recovers first; finalised is Completed whatever K. */
class FinalStageResolverTest {

  @Test
  void lockedUntilTheFinalFirstOpens() {
    for (int keys = 0; keys <= 4; keys++) {
      assertEquals(FinalStage.LOCKED, resolve(false, keys, false, false));
      assertEquals(FinalStage.LOCKED, resolve(false, keys, true, true));
    }
  }

  @Test
  void fourKeysGoStraightToTheCaseAndFewerRecoverFirst() {
    assertEquals(FinalStage.IN_FINAL, resolve(true, 4, false, false));
    for (int keys = 0; keys <= 3; keys++) {
      assertEquals(FinalStage.RECOVERY, resolve(true, keys, false, false), keys + " keys");
      assertEquals(FinalStage.IN_FINAL, resolve(true, keys, true, false), keys + " keys, recovery finished");
    }
  }

  @Test
  void aFinalisedTeamIsCompletedWhateverItsKeys() {
    for (int keys = 0; keys <= 4; keys++) {
      assertEquals(FinalStage.COMPLETED, resolve(true, keys, false, true));
      assertEquals(FinalStage.COMPLETED, resolve(true, keys, true, true));
    }
  }

  @Test
  void rejectsImpossibleKeyCounts() {
    assertThrows(IllegalArgumentException.class, () -> resolve(true, 5, false, false));
    assertThrows(IllegalArgumentException.class, () -> resolve(true, -1, false, false));
  }
}
