package dev.devfest.event.domain.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.recovery.RecoveryPath.Step;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The path by K and its strict order: ML's six stages, then ARDUINO, each once, never skipped. */
class RecoveryPathTest {

  private static final RecoveryLevel ML = RecoveryLevel.ML;
  private static final RecoveryLevel ARDUINO = RecoveryLevel.ARDUINO;

  @Test
  void thePathDependsOnTheKeysHeldWhenTheFinalOpened() {
    assertEquals(List.of(), RecoveryPath.requiredLevels(4));
    assertEquals(List.of(ML), RecoveryPath.requiredLevels(3));
    assertEquals(List.of(ML, ARDUINO), RecoveryPath.requiredLevels(2));
    assertEquals(List.of(ML, ARDUINO), RecoveryPath.requiredLevels(1));
    assertEquals(List.of(ML, ARDUINO), RecoveryPath.requiredLevels(0));
    assertThrows(IllegalArgumentException.class, () -> RecoveryPath.requiredLevels(5));
    assertThrows(IllegalArgumentException.class, () -> RecoveryPath.requiredLevels(-1));
  }

  @Test
  void stagesComeStrictlyInOrderAndNoneIsSkipped() {
    List<RecoveryLevel> path = RecoveryPath.requiredLevels(0);
    Set<Step> passed = new HashSet<>();
    for (int stage = 1; stage <= 6; stage++) {
      assertEquals(Optional.of(new Step(ML, stage)), RecoveryPath.current(path, passed));
      passed.add(new Step(ML, stage));
    }
    assertEquals(Optional.of(new Step(ARDUINO, 1)), RecoveryPath.current(path, passed));
    passed.add(new Step(ARDUINO, 1));
    assertEquals(Optional.empty(), RecoveryPath.current(path, passed));
  }

  @Test
  void aLaterStagePassedOutOfOrderDoesNotMoveTheCurrentStage() {
    Set<Step> passed = Set.of(new Step(ML, 3), new Step(ARDUINO, 1));
    assertEquals(Optional.of(new Step(ML, 1)), RecoveryPath.current(RecoveryPath.requiredLevels(1), passed));
  }

  @Test
  void threeKeysFinishAfterMlAndNeverMeetArduino() {
    List<RecoveryLevel> path = RecoveryPath.requiredLevels(3);
    Set<Step> allMl = new HashSet<>();
    for (int stage = 1; stage <= 6; stage++) allMl.add(new Step(ML, stage));
    assertEquals(Optional.empty(), RecoveryPath.current(path, allMl));
    assertTrue(RecoveryPath.isLast(path, new Step(ML, 6)));
    assertFalse(RecoveryPath.isLast(path, new Step(ML, 5)));
    assertFalse(RecoveryPath.isLast(RecoveryPath.requiredLevels(2), new Step(ML, 6)));
    assertTrue(RecoveryPath.isLast(RecoveryPath.requiredLevels(2), new Step(ARDUINO, 1)));
    assertFalse(RecoveryPath.isLast(List.of(), new Step(ML, 6)));
  }

  @Test
  void theCaseClockStartsAtZeroWithoutRecoveryAndAtTheFinishOtherwise() {
    assertEquals(0, RecoveryPath.caseStartSeconds(4, null));
    assertEquals(0, RecoveryPath.caseStartSeconds(4, 300), "a team with four keys never recovers");
    assertNull(RecoveryPath.caseStartSeconds(3, null));
    assertEquals(612, RecoveryPath.caseStartSeconds(3, 612));
    assertEquals(1500, RecoveryPath.caseStartSeconds(0, 1500));
  }

  @Test
  void theOrganizersTableNamesThePathAndTheCryptoWindow() {
    assertEquals("NONE", RecoveryPath.pathCode(4));
    assertEquals("ML", RecoveryPath.pathCode(3));
    assertEquals("ML_ARDUINO", RecoveryPath.pathCode(2));
    assertEquals("ML_ARDUINO", RecoveryPath.pathCode(1));
    assertEquals("ML_ARDUINO", RecoveryPath.pathCode(0));
    assertEquals(new RecoveryPath.CryptoWindow(0, null), RecoveryPath.cryptoWindow(0, null), "no fourth file: until the end");
    assertEquals(new RecoveryPath.CryptoWindow(0, 360), RecoveryPath.cryptoWindow(0, 360), "until the fourth file at 6:00");
    assertEquals(new RecoveryPath.CryptoWindow(600, 1080), RecoveryPath.cryptoWindow(600, 1080), "S = 10:00, fourth file at 18:00");
    assertNull(RecoveryPath.cryptoWindow(null, null), "no window until the recovery is finished");
  }

  @Test
  void aStepMustExistInItsLevel() {
    assertThrows(IllegalArgumentException.class, () -> new Step(ML, 7));
    assertThrows(IllegalArgumentException.class, () -> new Step(ML, 0));
    assertThrows(IllegalArgumentException.class, () -> new Step(ARDUINO, 2));
  }
}
