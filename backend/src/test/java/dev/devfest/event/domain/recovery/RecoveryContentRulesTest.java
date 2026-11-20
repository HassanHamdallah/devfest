package dev.devfest.event.domain.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The shipped recovery levels are valid, and every rule lists its own problem without stopping the others. */
class RecoveryContentRulesTest {

  private static List<String> problemsAfter(Consumer<ObjectNode> change) {
    ObjectNode tree = ShippedRecovery.tree();
    change.accept(tree);
    return RecoveryContentRules.problems(ShippedRecovery.read(tree));
  }

  private static ObjectNode ml(ObjectNode tree, String stage) {
    return (ObjectNode) tree.get("ml").get(stage);
  }

  private static ObjectNode arduino(ObjectNode tree) {
    return (ObjectNode) tree.get("arduino");
  }

  private static void assertProblem(String expected, List<String> problems) {
    assertTrue(problems.contains(expected), "missing <" + expected + "> in " + problems);
  }

  @Test
  void theShippedLevelsAreValid() {
    assertEquals(List.of(), RecoveryContentRules.problems(ShippedRecovery.levels()));
  }

  @Test
  void bothLevelsAreRequired() {
    assertEquals(List.of("recoveryLevels: required, with \"ml\" and \"arduino\"."), RecoveryContentRules.problems(null));
    assertEquals(List.of("recoveryLevels.ml: required."), problemsAfter(t -> t.remove("ml")));
    assertEquals(List.of("recoveryLevels.ml.threshold: required (stage 5)."), problemsAfter(t -> ((ObjectNode) t.get("ml")).remove("threshold")));
  }

  @Test
  void everyProblemIsListedAtOnce() {
    List<String> problems = problemsAfter(t -> {
      ml(t, "leakage").put("title", "");
      ml(t, "model").put("answer", "knn");
      ml(t, "evidence").put("answer", "");
      arduino(t).put("token", "");
      ((ObjectNode) arduino(t).get("slot")).put("correct", "Z");
    });
    assertProblem("recoveryLevels.ml.leakage.title is required (at most 120 characters).", problems);
    assertProblem("recoveryLevels.ml.model.answer: must be the only model that meets the requirement (models meeting it: [logreg]).", problems);
    assertProblem("recoveryLevels.ml.evidence.answer is required (at most 100 characters).", problems);
    assertProblem("recoveryLevels.arduino.token is required (at most 40 characters).", problems);
    assertProblem("recoveryLevels.arduino.slot.correct: must be the id of one of the options.", problems);
    assertEquals(5, problems.size(), problems.toString());
  }

  @Test
  void mlAnswersMustBeAmongTheChoices() {
    assertProblem(
      "recoveryLevels.ml.leakage.kept: every kept feature must be one of the features.",
      problemsAfter(t -> ((ArrayNode) ml(t, "leakage").get("kept")).add("label"))
    );
    assertProblem(
      "recoveryLevels.ml.pipeline.order: must list every step id exactly once.",
      problemsAfter(t -> ((ArrayNode) ml(t, "pipeline").get("order")).remove(0))
    );
    assertProblem(
      "recoveryLevels.ml.pipeline.order: must differ from startOrder.",
      problemsAfter(t -> ml(t, "pipeline").set("order", ml(t, "pipeline").get("startOrder").deepCopy()))
    );
    assertProblem(
      "recoveryLevels.ml.pipeline.relabel: item and after must be two different step ids.",
      problemsAfter(t -> ((ObjectNode) ml(t, "pipeline").get("relabel")).put("after", "nope"))
    );
    assertProblem(
      "recoveryLevels.ml.cleaning.answer: missing and outliers must each be one of their option ids.",
      problemsAfter(t -> ((ObjectNode) ml(t, "cleaning").get("answer")).put("missing", "mean"))
    );
    assertProblem(
      "recoveryLevels.ml.cleaning.table.rows[2]: must have one cell per column.",
      problemsAfter(t -> ((ArrayNode) ml(t, "cleaning").get("table").get("rows").get(1)).remove(0))
    );
    assertProblem("recoveryLevels.ml.model.answer: must be the id of one of the models.", problemsAfter(t -> ml(t, "model").put("answer", "svm")));
  }

  @Test
  void theThresholdMustBeReachableOnTheSlider() {
    assertProblem(
      "recoveryLevels.ml.threshold: no threshold on the slider meets the requirement.",
      problemsAfter(t -> ((ObjectNode) ml(t, "threshold").get("requirement")).put("minRecall", 0.99))
    );
    assertTrue(
      problemsAfter(t -> ((ObjectNode) ml(t, "threshold").get("slider")).put("step", 0.07)).getFirst().startsWith("recoveryLevels.ml.threshold.slider: min < max"),
      "a step that does not divide the range"
    );
    assertTrue(
      problemsAfter(t -> ((ObjectNode) ml(t, "threshold").get("slider")).put("min", 0.9)).getFirst().startsWith("recoveryLevels.ml.threshold.slider:")
    );
  }

  @Test
  void theProgramMustBeNumberedInOrder() {
    assertProblem(
      "recoveryLevels.arduino.lines[3]: n must be 3 (lines are numbered 1, 2, 3, ... in order).",
      problemsAfter(t -> ((ObjectNode) arduino(t).get("lines").get(2)).put("n", 7))
    );
    assertProblem(
      "recoveryLevels.arduino.lines[4]: code is required: one line of at most 300 characters (it may be empty).",
      problemsAfter(t -> ((ObjectNode) arduino(t).get("lines").get(3)).remove("code"))
    );
    assertProblem("recoveryLevels.arduino.slot.afterLine: must be from 0 to 89.", problemsAfter(t -> ((ObjectNode) arduino(t).get("slot")).put("afterLine", 90)));
  }

  @Test
  void bugsMustPointAtRealUnfixedLinesAndEveryBugNeedsATest() {
    ArrayNode bugs = (ArrayNode) ShippedRecovery.tree().get("arduino").get("bugs");
    assertEquals("B1", bugs.get(0).get("id").asString());
    assertProblem(
      "recoveryLevels.arduino.bugs.B1.lines: 120 is not a line of the program.",
      problemsAfter(t -> ((ObjectNode) arduino(t).get("bugs").get(0)).putArray("lines").add(120))
    );
    assertProblem(
      "recoveryLevels.arduino.bugs.B5.lines: line 40 belongs to another bug.",
      problemsAfter(t -> ((ObjectNode) arduino(t).get("bugs").get(4)).putArray("lines").add(40))
    );
    assertProblem(
      "recoveryLevels.arduino.bugs.B1.accepted: the program's own line 40 (authorized = true;) is accepted, so the bug is already fixed.",
      problemsAfter(t -> ((ArrayNode) arduino(t).get("bugs").get(0).get("accepted")).add("authorized = true;"))
    );
    assertProblem(
      "recoveryLevels.arduino.bugs.B3.kind: must be REPLACE or REMOVE.",
      problemsAfter(t -> ((ObjectNode) arduino(t).get("bugs").get(2)).put("kind", "DELETE"))
    );
    assertProblem(
      "recoveryLevels.arduino.bugs.B1: a REPLACE bug has exactly one line.",
      problemsAfter(t -> ((ArrayNode) arduino(t).get("bugs").get(0).get("lines")).add(41))
    );
    assertProblem(
      "recoveryLevels.arduino.tests: no test checks B4.",
      problemsAfter(t -> ((ArrayNode) arduino(t).get("tests").get(4).get("bugs")).removeAll())
    );
    assertProblem(
      "recoveryLevels.arduino.tests.T2.bugs: B9 is not a bug or the slot.",
      problemsAfter(t -> ((ArrayNode) arduino(t).get("tests").get(1).get("bugs")).add("B9"))
    );
    assertProblem(
      "recoveryLevels.arduino.bugs: the id B6 is used twice (bugs and the slot share ids).",
      problemsAfter(t -> ((ObjectNode) arduino(t).get("bugs").get(0)).put("id", "B6"))
    );
  }
}
