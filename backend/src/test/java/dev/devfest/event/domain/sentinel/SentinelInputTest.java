package dev.devfest.event.domain.sentinel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.ContentRejectedException;
import dev.devfest.event.domain.sentinel.SentinelContent.Answer;
import dev.devfest.event.domain.sentinel.SentinelContent.Input;
import dev.devfest.event.domain.sentinel.SentinelContent.NumberAnswer;
import dev.devfest.event.domain.sentinel.SentinelContent.Stage;
import dev.devfest.event.domain.sentinel.SentinelContent.TextAnswer;
import dev.devfest.event.dto.MissionDocument;
import dev.devfest.event.dto.StageAnswers;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The typed answer kind (rules §19.2): how an answer is read and judged, how a file is validated, and its figures. */
class SentinelInputTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final TextAnswer BITS = new TextAnswer(List.of("101001"));
  private static final NumberAnswer GAIN = new NumberAnswer(0.19, 0.01);
  private static final NumberAnswer WHOLE = new NumberAnswer(4, 0);

  private static SentinelContent content() {
    return MissionDocument.read(JSON.readTree(SentinelFixture.shipped())).content();
  }

  private static ObjectNode stage(ObjectNode root, int number) {
    int index = number - 1;
    int mission = index < 3 ? 0 : index < 6 ? 1 : 2;
    int inside = index < 3 ? index : index < 6 ? index - 3 : index - 6;
    return (ObjectNode) root.get("missions").get(mission).get("stages").get(inside);
  }

  private static List<String> rejected(Consumer<ObjectNode> change) {
    ObjectNode root = (ObjectNode) JSON.readTree(SentinelFixture.shipped());
    change.accept(root);
    return assertThrows(ContentRejectedException.class, () -> MissionDocument.read(root)).problems();
  }

  private static BigDecimal points(int stage, String answerJson) {
    Stage s = content().stage(stage);
    return SentinelScoring.points(s, StageAnswers.read(s, JSON.readTree(answerJson)));
  }

  // ---- TEXT ----------------------------------------------------------------------------------------

  @Test
  void textIsTrimmedWithoutAnySpaceAndCaseInsensitive() {
    assertEquals("101001", InputAnswers.normalize("  101 001 "));
    assertEquals("101001", InputAnswers.normalize("1 0 1 0 0 1"));
    assertEquals("101001", InputAnswers.normalize("101\t001\n"));
    assertEquals("101001", InputAnswers.normalize("101 001"), "a non-breaking space too");
    assertEquals("abc", InputAnswers.normalize("A b C"));
    assertEquals("", InputAnswers.normalize("   "));
    assertEquals("", InputAnswers.normalize(null));
  }

  @Test
  void aTextAnswerIsCorrectInAnyOfTheseFormsAndWrongOtherwise() {
    for (String right : List.of("101001", " 101001 ", "101 001", "1 0 1 0 0 1", "10100 1")) assertTrue(InputAnswers.correct(BITS, right), right);
    for (String wrong : List.of("101011", "10100", "1010010", "", "   ", "abc", "101|001", "101-001")) assertFalse(InputAnswers.correct(BITS, wrong), wrong);
    TextAnswer words = new TextAnswer(List.of("Cybersecurity Office", "SOC"));
    assertTrue(InputAnswers.correct(words, "cybersecurityoffice"));
    assertTrue(InputAnswers.correct(words, "CYBERSECURITY  OFFICE"));
    assertTrue(InputAnswers.correct(words, "soc"));
    assertFalse(InputAnswers.correct(words, "security office"));
  }

  @Test
  void anEmptyTextAnswerIsRefusedAndTooLongOnesToo() {
    assertEquals(Optional.of("Enter your answer."), InputAnswers.refusal(BITS, ""));
    assertEquals(Optional.of("Enter your answer."), InputAnswers.refusal(BITS, "   "));
    assertEquals(Optional.of("Enter your answer."), InputAnswers.refusal(BITS, null));
    assertEquals(Optional.empty(), InputAnswers.refusal(BITS, "x".repeat(40)));
    assertEquals(Optional.of("The answer can have at most 40 characters."), InputAnswers.refusal(BITS, "x".repeat(41)));
    assertEquals(Optional.empty(), InputAnswers.refusal(BITS, " " + "x".repeat(40) + " "), "spaces around it do not count");
  }

  // ---- NUMBER --------------------------------------------------------------------------------------

  @Test
  void numbersAreReadAsPlainDecimalsWithAPointOrAComma() {
    assertEquals(Optional.of(0.19), InputAnswers.parseNumber("0.19"));
    assertEquals(Optional.of(0.19), InputAnswers.parseNumber(".19"));
    assertEquals(Optional.of(0.19), InputAnswers.parseNumber("0,19"));
    assertEquals(Optional.of(0.19), InputAnswers.parseNumber(",19"));
    assertEquals(Optional.of(0.189), InputAnswers.parseNumber("0.189"));
    assertEquals(Optional.of(4.0), InputAnswers.parseNumber("4"));
    assertEquals(Optional.of(4.0), InputAnswers.parseNumber("4."));
    assertEquals(Optional.of(4.0), InputAnswers.parseNumber(" 4 "));
    assertEquals(Optional.of(-2.5), InputAnswers.parseNumber("-2.5"));
    assertEquals(Optional.of(3.0), InputAnswers.parseNumber("+3"));
    assertEquals(Optional.of(0.0), InputAnswers.parseNumber("000"));
  }

  @Test
  void anythingThatIsNotAPlainDecimalIsNotANumber() {
    for (String bad : List.of("", " ", "abc", "1e3", "1E-2", "0x10", "1,000.5", "1.000,5", "1.2.3", "--1", "+-1", ".", ",", "-", "4 5", "4 %", "½", "0.19.", "NaN", "Infinity", "٣")) {
      assertEquals(Optional.empty(), InputAnswers.parseNumber(bad), "'" + bad + "'");
    }
    assertEquals(Optional.empty(), InputAnswers.parseNumber(null));
  }

  @Test
  void theToleranceBoundsAreInclusive() {
    for (String right : List.of("0.19", ".19", "0,19", "0.189", "0.18", "0.20", "0.2", "0,2", "0.1900001")) assertTrue(InputAnswers.correct(GAIN, right), right);
    for (String wrong : List.of("0.17", "0.21", "0.2101", "0.179", "0", "1", "-0.19", "abc", "")) assertFalse(InputAnswers.correct(GAIN, wrong), wrong);
    for (String right : List.of("4", "4.0", "4,0", "04", "+4", " 4 ")) assertTrue(InputAnswers.correct(WHOLE, right), right);
    for (String wrong : List.of("3.99", "4.01", "5", "-4", "40")) assertFalse(InputAnswers.correct(WHOLE, wrong), wrong);
  }

  @Test
  void anAnswerThatIsNotANumberIsRefusedWithAHintAndAnEmptyOneToo() {
    for (String bad : List.of("", "  ", "abc", "1e3", "1,000.5", "zero")) {
      assertEquals(Optional.of("Enter a number, for example 0.5."), InputAnswers.refusal(GAIN, bad), "'" + bad + "'");
    }
    assertEquals(Optional.empty(), InputAnswers.refusal(GAIN, ".19"));
    assertEquals(Optional.empty(), InputAnswers.refusal(GAIN, "0,19"));
    assertEquals(Optional.of("The answer can have at most 40 characters."), InputAnswers.refusal(GAIN, "0." + "1".repeat(40)));
  }

  // ---- scoring through the real file ----------------------------------------------------------------

  @Test
  void theShippedStagesSixEightAndNineScoreAllOrNothing() {
    assertEquals(new BigDecimal("10.00"), points(6, "{\"answer\":\"101 001\"}"));
    assertEquals(new BigDecimal("10.00"), points(6, "{\"answer\":\" 101001\"}"));
    assertEquals(new BigDecimal("0.00"), points(6, "{\"answer\":\"101011\"}"));
    assertEquals(new BigDecimal("10.00"), points(8, "{\"answer\":\".19\"}"));
    assertEquals(new BigDecimal("10.00"), points(8, "{\"answer\":\"0,19\"}"));
    assertEquals(new BigDecimal("10.00"), points(8, "{\"answer\":\"0.189\"}"));
    assertEquals(new BigDecimal("0.00"), points(8, "{\"answer\":\"0.81\"}"));
    assertEquals(new BigDecimal("10.00"), points(9, "{\"answer\":\"4\"}"));
    assertEquals(new BigDecimal("0.00"), points(9, "{\"answer\":\"4.5\"}"));
    BigDecimal total = BigDecimal.ZERO;
    for (int stage = 1; stage <= 10; stage++) total = total.add(points(stage, SentinelFixture.PERFECT.get(stage - 1)));
    assertEquals(0, total.compareTo(BigDecimal.valueOf(100)));
  }

  @Test
  void aRefusedAnswerThrowsWithItsMessageInsteadOfScoringZero() {
    IllegalArgumentException number = assertThrows(IllegalArgumentException.class, () -> points(8, "{\"answer\":\"abc\"}"));
    assertEquals("Enter a number, for example 0.5.", number.getMessage());
    assertEquals("Enter a number, for example 0.5.", assertThrows(IllegalArgumentException.class, () -> points(9, "{\"answer\":\"\"}")).getMessage());
    assertEquals("Enter your answer.", assertThrows(IllegalArgumentException.class, () -> points(6, "{\"answer\":\"\"}")).getMessage());
    assertEquals("The answer can have at most 40 characters.", assertThrows(IllegalArgumentException.class, () -> points(6, "{\"answer\":\"" + "1".repeat(41) + "\"}")).getMessage());
  }

  @Test
  void aTypedBodyHasOnlyTheAnswerFieldAsAText() {
    Stage stage = content().stage(6);
    for (String bad : List.of("{}", "[]", "{\"answer\":5}", "{\"answer\":null}", "{\"answer\":\"1\",\"extra\":1}", "{\"option\":\"a\"}", "{\"config\":\"a\"}")) {
      assertThrows(IllegalArgumentException.class, () -> StageAnswers.read(stage, JSON.readTree(bad)), bad);
    }
    assertThrows(IllegalArgumentException.class, () -> StageAnswers.read(stage, null));
    assertThrows(IllegalArgumentException.class, () -> SentinelScoring.points(stage, new StageAnswer.Option("a")), "an option is not a typed answer");
    assertFalse(SentinelRuns.hasRuns(stage), "a typed stage has no experiments");
  }

  // ---- validation ----------------------------------------------------------------------------------

  @Test
  void aTextAnswerNeedsOneToTenNonEmptyValues() {
    assertEquals(List.of(), InputAnswers.problems(BITS));
    assertEquals(List.of("answer.accepted needs 1 to 10 values."), InputAnswers.problems(new TextAnswer(List.of())));
    assertEquals(List.of("An accepted value cannot be empty."), InputAnswers.problems(new TextAnswer(List.of("  "))));
    assertEquals(11, 11);
    List<String> eleven = java.util.stream.IntStream.range(0, 11).mapToObj(i -> "v" + i).toList();
    assertEquals(List.of("answer.accepted needs 1 to 10 values."), InputAnswers.problems(new TextAnswer(eleven)));
    assertEquals(List.of(), InputAnswers.problems(new TextAnswer(eleven.subList(0, 10))));
    assertTrue(InputAnswers.problems(new TextAnswer(List.of("x".repeat(41)))).get(0).contains("longer than the 40 characters"));
  }

  @Test
  void aNumberAnswerNeedsAFiniteValueAndANonNegativeTolerance() {
    assertEquals(List.of(), InputAnswers.problems(GAIN));
    assertEquals(List.of(), InputAnswers.problems(WHOLE));
    assertEquals(List.of("answer.value must be a finite number."), InputAnswers.problems(new NumberAnswer(Double.NaN, 0)));
    assertEquals(List.of("answer.value must be a finite number."), InputAnswers.problems(new NumberAnswer(Double.POSITIVE_INFINITY, 0)));
    assertEquals(List.of("answer.tolerance must be 0 or more."), InputAnswers.problems(new NumberAnswer(1, -0.01)));
    assertEquals(List.of("answer.tolerance must be 0 or more."), InputAnswers.problems(new NumberAnswer(1, Double.NaN)));
  }

  @Test
  void theFileIsValidatedForItsAnswerKindsWorthAndKind() {
    List<String> noAccepted = rejected(root -> ((ObjectNode) stage(root, 6).get("answer")).set("accepted", JSON.createArrayNode()));
    assertTrue(noAccepted.stream().anyMatch(p -> p.contains("Stage 6") && p.contains("accepted")), noAccepted.toString());
    List<String> emptyValue = rejected(root -> ((ObjectNode) stage(root, 6).get("answer")).set("accepted", JSON.createArrayNode().add("  ")));
    assertTrue(emptyValue.stream().anyMatch(p -> p.startsWith("Stage 6") && p.contains("accepted")), emptyValue.toString());
    List<String> negative = rejected(root -> ((ObjectNode) stage(root, 8).get("answer")).put("tolerance", -1));
    assertTrue(negative.contains("Stage 8: answer.tolerance must be 0 or more."), negative.toString());
    List<String> kind = rejected(root -> ((ObjectNode) stage(root, 9).get("answer")).put("kind", "WORDS"));
    assertTrue(kind.stream().anyMatch(p -> p.contains("kind must be TEXT or NUMBER")), kind.toString());
    List<String> missing = rejected(root -> stage(root, 9).remove("answer"));
    assertTrue(missing.stream().anyMatch(p -> p.contains("Stage 9 · answer: must be an object.")), missing.toString());
    List<String> extra = rejected(root -> ((ObjectNode) stage(root, 8).get("answer")).put("accepted", "x"));
    assertTrue(extra.stream().anyMatch(p -> p.contains("accepted: is not a field")), extra.toString());
    List<String> worthless = rejected(root -> stage(root, 8).put("maxPoints", 0));
    assertTrue(worthless.contains("Stage 8: The stage must be worth more than 0 points."), worthless.toString());
    List<String> wrongStage = rejected(root -> stage(root, 7).put("type", "INPUT"));
    assertTrue(wrongStage.stream().anyMatch(p -> p.contains("type INPUT cannot be stage 7 (stage 7 is ROUTE_PLANNER).")), wrongStage.toString());
    List<String> choiceAtSix = rejected(root -> stage(root, 6).put("type", "CHOICE"));
    assertTrue(choiceAtSix.stream().anyMatch(p -> p.contains("type CHOICE belongs to stage 3, not stage 6.")), choiceAtSix.toString());
  }

  // ---- the figures ---------------------------------------------------------------------------------

  @Test
  void theShippedFiguresAreExactlyTheQuestionsData() {
    SentinelContent content = content();
    SentinelContent.NeuronFigure neuron = (SentinelContent.NeuronFigure) ((SentinelContent.Choice) content.stage(3)).figure();
    assertEquals(List.of("x1", "x2"), neuron.inputs().stream().map(SentinelContent.NeuronInput::label).toList());
    assertEquals(List.of(3.0, 2.0), neuron.inputs().stream().map(SentinelContent.NeuronInput::value).toList());
    assertEquals(List.of(0.5, -2.0), neuron.weights());
    assertEquals(1.0, neuron.bias());
    SentinelContent.CrossoverFigure crossover = (SentinelContent.CrossoverFigure) ((Input) content.stage(6)).figure();
    assertEquals(new SentinelContent.CrossoverFigure("101100", "011011", 3, 5), crossover);
    SentinelContent.SplitFigure split = (SentinelContent.SplitFigure) ((Input) content.stage(8)).figure();
    assertEquals(new SentinelContent.SplitFigure(new SentinelContent.Counts(4, 4), "After closing time?", new SentinelContent.Counts(1, 3), new SentinelContent.Counts(3, 1)), split);
    SentinelContent.MinimaxFigure minimax = (SentinelContent.MinimaxFigure) ((Input) content.stage(9)).figure();
    assertEquals(new SentinelContent.MinimaxFigure("MAX", List.of(List.of(3.0, 5.0), List.of(2.0, 9.0), List.of(4.0, 6.0))), minimax);
  }

  @Test
  void aFigureThatDisagreesWithItsBriefIsRefusedWithAReason() {
    List<String> neuron = rejected(root -> ((ObjectNode) stage(root, 3).get("figure").get("inputs").get(0)).put("value", 7));
    assertTrue(neuron.contains("Stage 3: figure: the brief does not say \"x1 = 7\"."), neuron.toString());
    List<String> weight = rejected(root -> ((tools.jackson.databind.node.ArrayNode) stage(root, 3).get("figure").get("weights")).set(1, JSON.getNodeFactory().numberNode(-3)));
    assertTrue(weight.contains("Stage 3: figure: the brief does not say \"w2 = -3\"."), weight.toString());
    List<String> bias = rejected(root -> ((ObjectNode) stage(root, 3).get("figure")).put("bias", 2));
    assertTrue(bias.contains("Stage 3: figure: the brief does not say \"b = 2\"."), bias.toString());
    List<String> counts = rejected(root -> ((tools.jackson.databind.node.ArrayNode) stage(root, 3).get("figure").get("weights")).remove(1));
    assertTrue(counts.stream().anyMatch(p -> p.contains("one weight for every input")), counts.toString());

    List<String> parent = rejected(root -> ((ObjectNode) stage(root, 6).get("figure")).put("parentA", "101101"));
    assertTrue(parent.contains("Stage 6: figure: Parent A in the brief is not 101101."), parent.toString());
    List<String> cut = rejected(root -> ((ObjectNode) stage(root, 6).get("figure")).put("cut", 4));
    assertTrue(cut.contains("Stage 6: figure: the brief does not say \"Crossover after position 4\"."), cut.toString());
    List<String> mutate = rejected(root -> ((ObjectNode) stage(root, 6).get("figure")).put("mutate", 9));
    assertTrue(mutate.stream().anyMatch(p -> p.contains("mutate must be from 1 to 6.")), mutate.toString());
    List<String> bits = rejected(root -> ((ObjectNode) stage(root, 6).get("figure")).put("parentB", "01101"));
    assertTrue(bits.stream().anyMatch(p -> p.contains("the parents must have the same number of bits.")), bits.toString());

    List<String> sum = rejected(root -> ((ObjectNode) stage(root, 8).get("figure").get("yes")).put("normal", 2));
    assertTrue(sum.stream().anyMatch(p -> p.contains("the two branches must add up to the parent node.")), sum.toString());
    assertTrue(sum.stream().anyMatch(p -> p.contains("the brief does not say \"Yes: 2 normal, 3 suspicious\".")), sum.toString());
    List<String> question = rejected(root -> ((ObjectNode) stage(root, 8).get("figure")).put("question", "Before closing time?"));
    assertTrue(question.stream().anyMatch(p -> p.contains("the brief does not quote the question \"Before closing time?\".")), question.toString());

    List<String> leaf = rejected(root -> ((tools.jackson.databind.node.ArrayNode) stage(root, 9).get("figure").get("children").get(1)).set(1, JSON.getNodeFactory().numberNode(8)));
    assertTrue(leaf.stream().anyMatch(p -> p.startsWith("Stage 9: figure: the leaf values must be the ones the brief lists after \"MIN:\"")), leaf.toString());
    List<String> root = rejected(r -> ((ObjectNode) stage(r, 9).get("figure")).put("root", "BOTH"));
    assertTrue(root.contains("Stage 9: figure: root must be MAX or MIN."), root.toString());
  }

  @Test
  void aFigureHasNoFieldForAnAnswerOrAnIntermediateResult() {
    List<String> extra = rejected(root -> ((ObjectNode) stage(root, 8).get("figure")).put("entropy", 0.811));
    assertTrue(extra.contains("Stage 8 · figure.entropy: is not a field of this part of the file."), extra.toString());
    List<String> result = rejected(root -> ((ObjectNode) stage(root, 3).get("figure")).put("z", -1.5));
    assertTrue(result.contains("Stage 3 · figure.z: is not a field of this part of the file."), result.toString());
    List<String> type = rejected(root -> ((ObjectNode) stage(root, 3).get("figure")).put("type", "pie"));
    assertTrue(type.stream().anyMatch(p -> p.contains("type must be one of neuron, crossover, split, minimax.")), type.toString());
  }

  @Test
  void aStageWithoutAFigureIsFineAndNumbersAreWrittenAsABriefWritesThem() {
    assertEquals(List.of(), FigureRules.problems(null, "anything"));
    assertEquals("3", FigureRules.number(3.0));
    assertEquals("0.5", FigureRules.number(0.5));
    assertEquals("-2", FigureRules.number(-2.0));
    assertEquals("-0.415", FigureRules.number(-0.415));
    ObjectNode root = (ObjectNode) JSON.readTree(SentinelFixture.shipped());
    stage(root, 6).remove("figure");
    assertEquals(List.of(), SentinelContentRules.problems(MissionDocument.read(root).content()));
  }
}
