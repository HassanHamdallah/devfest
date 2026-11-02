package dev.devfest.event.domain;

import static dev.devfest.event.domain.FinalStageResolver.resolve;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FinalStageResolverTest {

  @Test
  void finishedWinsEvenAfterTheFinalCloses() {
    assertEquals(FinalStage.FINISHED, resolve(true, true, true, 4));
    assertEquals(FinalStage.FINISHED, resolve(true, false, true, 4));
  }

  @Test
  void lockedUntilTheFinalFirstOpens() {
    for (int clues = 0; clues <= 4; clues++) {
      assertEquals(FinalStage.LOCKED, resolve(false, false, false, clues));
    }
  }

  @Test
  void closedAfterTheFinalClosesForUnfinishedTeams() {
    for (int clues = 0; clues <= 4; clues++) {
      assertEquals(FinalStage.CLOSED, resolve(true, false, false, clues));
    }
  }

  @Test
  void openFinalStageDependsOnCollectedClues() {
    assertEquals(FinalStage.MESSAGE, resolve(true, true, false, 4));
    assertEquals(FinalStage.RECOVERY, resolve(true, true, false, 3));
    assertEquals(FinalStage.NOT_ENOUGH_CLUES, resolve(true, true, false, 2));
    assertEquals(FinalStage.NOT_ENOUGH_CLUES, resolve(true, true, false, 0));
  }

  @Test
  void rejectsImpossibleClueCounts() {
    assertThrows(IllegalArgumentException.class, () -> resolve(true, true, false, 5));
    assertThrows(IllegalArgumentException.class, () -> resolve(true, true, false, -1));
  }
}
