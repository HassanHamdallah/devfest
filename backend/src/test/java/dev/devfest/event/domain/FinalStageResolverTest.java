package dev.devfest.event.domain;

import static dev.devfest.event.domain.FinalStageResolver.resolve;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The qualification matrix: 4 clues start, 3 clues recover, 0-2 clues do not qualify. */
class FinalStageResolverTest {

  @Test
  void lockedUntilTheFinalFirstOpens() {
    for (int clues = 0; clues <= 4; clues++) {
      assertEquals(FinalStage.LOCKED, resolve(false, clues, false));
    }
  }

  @Test
  void openFinalStatusDependsOnCollectedClues() {
    assertEquals(FinalStage.IN_FINAL, resolve(true, 4, false));
    assertEquals(FinalStage.RECOVERY, resolve(true, 3, false));
    assertEquals(FinalStage.NOT_QUALIFIED, resolve(true, 2, false));
    assertEquals(FinalStage.NOT_QUALIFIED, resolve(true, 1, false));
    assertEquals(FinalStage.NOT_QUALIFIED, resolve(true, 0, false));
  }

  @Test
  void aFinalisedTeamIsCompletedOnlyIfItQualified() {
    assertEquals(FinalStage.COMPLETED, resolve(true, 4, true));
    assertEquals(FinalStage.COMPLETED, resolve(true, 3, true));
    assertEquals(FinalStage.NOT_QUALIFIED, resolve(true, 2, true));
    assertEquals(FinalStage.NOT_QUALIFIED, resolve(true, 0, true));
  }

  @Test
  void rejectsImpossibleClueCounts() {
    assertThrows(IllegalArgumentException.class, () -> resolve(true, 5, false));
    assertThrows(IllegalArgumentException.class, () -> resolve(true, -1, false));
  }
}
