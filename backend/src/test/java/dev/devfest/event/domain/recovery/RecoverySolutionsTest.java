package dev.devfest.event.domain.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.recovery.RecoverySolutions.ArduinoSolution;
import dev.devfest.event.domain.recovery.RecoverySolutions.BugSolution;
import dev.devfest.event.domain.recovery.RecoverySolutions.LineFix;
import dev.devfest.event.domain.recovery.RecoverySolutions.MlSolution;
import dev.devfest.event.domain.recovery.RecoverySolutions.OptionSolution;
import dev.devfest.event.domain.recovery.RecoverySolutions.Solutions;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The organizers' solutions are built from the content, so they must be exactly what the checkers accept. */
class RecoverySolutionsTest {

  private static final RecoveryLevels LEVELS = ShippedRecovery.levels();
  private static final Solutions SOLUTIONS = RecoverySolutions.of(LEVELS);

  private static MlSolution ml(int stage) {
    return SOLUTIONS.ml().get(stage - 1);
  }

  private static BugSolution bug(String id) {
    return SOLUTIONS.arduino().bugs().stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
  }

  @Test
  void everyMlStageHasItsAnswerAndItsReason() {
    assertEquals(6, SOLUTIONS.ml().size());
    for (int stage = 1; stage <= 6; stage++) {
      assertEquals(stage, ml(stage).stage());
      assertFalse(ml(stage).answer().isEmpty(), "stage " + stage);
      assertNotNull(ml(stage).why(), "stage " + stage);
    }
    assertEquals(List.of("Keep: motion_score, access_attempts, signal_strength, device_type", "Remove: investigation_result"), ml(1).answer());
    assertEquals(
      List.of("1. Load Dataset", "2. Split Train/Test", "3. Fit Scaler on Training Data", "4. Transform Train/Test", "5. Train Model", "6. Evaluate"),
      ml(2).answer()
    );
    assertEquals(List.of("Missing values: Impute using training median", "Outliers: Clip / flag unreasonable values"), ml(3).answer());
    assertEquals("Choose Logistic Regression (precision 0.79, recall 0.89)", ml(4).answer().getFirst());
    assertTrue(ml(4).answer().contains("Not KNN: recall 0.71 is below 0.88"), ml(4).answer().toString());
    assertTrue(ml(4).answer().contains("Not Decision Tree: precision 0.68 is below 0.75"), ml(4).answer().toString());
    assertEquals("Any threshold from 0.42 to 0.53 (12 slider steps).", ml(5).answer().getFirst());
    assertEquals("Word: CLUE", ml(6).answer().getLast());
    assertEquals("21:13  #07  NODE-C  → C", ml(6).answer().getFirst());
  }

  @Test
  void theMlAnswersAreThoseTheChecksAccept() {
    Ml ml = new Ml(LEVELS.ml());
    assertTrue(ml.passes(1, new MlChecks.Features(LEVELS.ml().leakage().kept())));
    assertTrue(ml.passes(2, new MlChecks.Order(LEVELS.ml().pipeline().order())));
    assertTrue(ml.passes(4, new MlChecks.ModelPick(LEVELS.ml().model().answer())));
    assertTrue(ml.passes(6, new MlChecks.Word(LEVELS.ml().evidence().answer())));
  }

  /** The ML answers by stage, for the check above. */
  private record Ml(RecoveryLevels.Ml ml) {
    boolean passes(int stage, MlChecks.Answer answer) {
      return MlChecks.passes(ml, stage, answer);
    }
  }

  @Test
  void theArduinoBugListNamesTheLinesAndTheTestsThatFailWhileTheyStay() {
    ArduinoSolution arduino = SOLUTIONS.arduino();
    assertEquals("security_controller.ino", arduino.fileName());
    assertEquals(List.of("B1", "B2", "B3", "B4", "B5", "B6"), arduino.bugs().stream().map(BugSolution::id).toList());
    assertEquals(List.of(new LineFix(40, "authorized = true;", "authorized = false;")), bug("B1").lines());
    assertEquals(List.of("T2 Authorization expires after 5 seconds"), bug("B1").failingTests());
    assertEquals("if (doorOpen && authorized == true) {", bug("B2").lines().getFirst().original());
    assertEquals(List.of("T3 Unauthorized door opening triggers alarm", "T4 Authorized opening does not trigger alarm"), bug("B2").failingTests());
    assertEquals(List.of(50, 51, 52), bug("B3").lines().stream().map(LineFix::line).toList());
    assertEquals("Comment out or delete this line", bug("B3").lines().getFirst().fix());
    assertEquals(List.of(56), bug("B4").lines().stream().map(LineFix::line).toList());
    assertEquals(List.of(75), bug("B5").lines().stream().map(LineFix::line).toList());
    assertEquals(List.of(), bug("B6").lines(), "the missing behaviour is the slot, not a line");
    assertEquals(List.of("T7 Authorization ends after valid entry"), bug("B6").failingTests());
    assertTrue(arduino.tests().contains("T1 Authorization starts correctly (passes unchanged)"), arduino.tests().toString());
  }

  @Test
  void optionBIsRightAndEveryWrongOptionSaysWhy() {
    var slot = SOLUTIONS.arduino().slot();
    assertEquals(88, slot.afterLine());
    assertEquals("B", slot.correctOption());
    List<OptionSolution> options = slot.options();
    assertEquals(List.of("A", "B", "C", "D"), options.stream().map(OptionSolution::id).toList());
    assertEquals(List.of(false, true, false, false), options.stream().map(OptionSolution::correct).toList());
    assertTrue(options.get(0).why().contains("before the door was ever opened"), options.get(0).why());
    for (OptionSolution option : options) assertNotNull(option.why(), option.id());
  }

  @Test
  void theFixedLinesAndTheRightOptionPassEveryHiddenTest() {
    List<String> lines = ShippedRecovery.originalLines();
    for (BugSolution bug : SOLUTIONS.arduino().bugs()) {
      for (LineFix fix : bug.lines()) {
        lines.set(fix.line() - 1, "REMOVE".equals(bug.kind()) ? "  // removed" : fix.fix());
      }
    }
    var run = ArduinoCheck.run(LEVELS.arduino(), lines, SOLUTIONS.arduino().slot().correctOption());
    assertTrue(run.passed(), run.tests().toString());
    var withA = ArduinoCheck.run(LEVELS.arduino(), lines, "A");
    assertFalse(withA.passed(), "option A fails");
    assertEquals(List.of("T7"), withA.tests().stream().filter(t -> !t.passed()).map(ArduinoCheck.TestResult::id).toList());
  }
}
