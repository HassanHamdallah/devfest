package dev.devfest.event.domain.sentinel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.ContentRejectedException;
import dev.devfest.event.domain.sentinel.SentinelContent.Choice;
import dev.devfest.event.domain.sentinel.SentinelContent.ChoiceOption;
import dev.devfest.event.domain.sentinel.SentinelContent.Stage;
import dev.devfest.event.dto.MissionDocument;
import dev.devfest.event.dto.StageAnswers;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The multiple choice stage kind (rules §19.2): its validation, its scoring, its answers and its per-team option order. */
class SentinelChoiceTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

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

  private static ArrayNode options(ObjectNode stage) {
    return (ArrayNode) stage.get("options");
  }

  private static BigDecimal points(int stage, String answer) {
    Stage s = content().stage(stage);
    return SentinelScoring.points(s, StageAnswers.read(s, JSON.readTree(answer)));
  }

  // ---- the stage kinds are fixed by number ----------------------------------------------------------

  @Test
  void stageThreeIsChoiceStagesSixEightAndNineAreInputAndTheOthersKeepTheirKinds() {
    assertEquals(
      List.of("OUTLIER_HUNT", "FEATURE_CHECK", "CHOICE", "NEAREST_VEHICLE", "CAMERA_ACCURACY", "INPUT", "ROUTE_PLANNER", "INPUT", "INPUT", "RAG_FIX"),
      SentinelContentRules.STAGE_KINDS
    );
    assertEquals(List.of("TUNE_DEPLOY", "GROUP_FRAMES", "THREAT_FILTER", "RULE_ENGINE"), SentinelContentRules.LEGACY_KINDS);
    assertEquals(SentinelContentRules.STAGE_KINDS, content().stages().stream().map(SentinelContentRules::kind).toList());
    assertEquals(List.of("predict", "see", "noor"), SentinelContentRules.MISSION_IDS, "the internal mission ids stay");
  }

  @Test
  void aChoiceStageCannotSitAtAnotherStagesNumber() {
    List<String> problems = rejected(root -> stage(root, 4).put("type", "CHOICE").put("correct", "a").set("options", stage(root, 3).get("options").deepCopy()));
    assertTrue(problems.stream().anyMatch(p -> p.contains("type CHOICE belongs to stage 3, not stage 4.")), problems.toString());
  }

  @Test
  void aNewFileCannotUseTheRetiredKinds() {
    List<String> problems = rejected(root -> stage(root, 3).put("type", "TUNE_DEPLOY"));
    assertTrue(problems.stream().anyMatch(p -> p.contains("type must be one of")), problems.toString());
  }

  // ---- validation -----------------------------------------------------------------------------------

  @Test
  void theNumberOfOptionsIsTwoToSix() {
    List<String> one = rejected(root -> {
      ArrayNode list = options(stage(root, 3));
      while (list.size() > 1) list.remove(list.size() - 1);
    });
    assertTrue(one.contains("Stage 3: Add 2 to 6 options."), one.toString());
    List<String> seven = rejected(root -> {
      ArrayNode list = options(stage(root, 3));
      for (String id : List.of("e", "f", "g")) list.addObject().put("id", id).put("text", id);
    });
    assertTrue(seven.contains("Stage 3: Add 2 to 6 options."), seven.toString());
    rejected(root -> {
      ArrayNode list = options(stage(root, 3));
      list.addObject().put("id", "e").put("text", "5");
      list.addObject().put("id", "f").put("text", "6");
      list.addObject().put("id", "g").put("text", "7");
    });
    ObjectNode root = (ObjectNode) JSON.readTree(SentinelFixture.shipped());
    ArrayNode six = options(stage(root, 3));
    six.addObject().put("id", "e").put("text", "5");
    six.addObject().put("id", "f").put("text", "6");
    assertEquals(6, ((Choice) MissionDocument.read(root).content().stage(3)).options().size(), "six options are fine");
    ObjectNode two = (ObjectNode) JSON.readTree(SentinelFixture.shipped());
    ArrayNode few = options(stage(two, 3));
    while (few.size() > 2) few.remove(few.size() - 1);
    assertEquals(2, ((Choice) MissionDocument.read(two).content().stage(3)).options().size(), "two options are fine");
  }

  @Test
  void theCorrectIdMustBeOneOfTheOptions() {
    assertTrue(rejected(root -> stage(root, 3).put("correct", "z")).contains("Stage 3: correct must be the id of one of the options."));
    assertTrue(rejected(root -> stage(root, 3).put("correct", "A")).contains("Stage 3: correct must be the id of one of the options."), "ids are lowercase");
    List<String> missing = rejected(root -> stage(root, 3).remove("correct"));
    assertTrue(missing.stream().anyMatch(p -> p.startsWith("Stage 3 · correct:")), missing.toString());
  }

  @Test
  void optionIdsAreUniqueSingleLettersAToZ() {
    List<String> duplicate = rejected(root -> ((ObjectNode) options(stage(root, 3)).get(1)).put("id", "a"));
    assertTrue(duplicate.contains("Stage 3: Two options share the id \"a\"."), duplicate.toString());
    List<String> upper = rejected(root -> ((ObjectNode) options(stage(root, 3)).get(1)).put("id", "B"));
    assertTrue(upper.stream().anyMatch(p -> p.startsWith("Stage 3: Option id \"B\" must be one lowercase letter")), upper.toString());
    List<String> long2 = rejected(root -> ((ObjectNode) options(stage(root, 3)).get(1)).put("id", "ab"));
    assertTrue(long2.stream().anyMatch(p -> p.startsWith("Stage 3: Option id \"ab\" must be one lowercase letter")), long2.toString());
    List<String> digit = rejected(root -> ((ObjectNode) options(stage(root, 3)).get(1)).put("id", "1"));
    assertTrue(digit.stream().anyMatch(p -> p.startsWith("Stage 3: Option id \"1\" must be one lowercase letter")), digit.toString());
  }

  @Test
  void aChoiceStageMustBeWorthMoreThanZeroAndTheStagesStillAddUpTo100() {
    List<String> zero = rejected(root -> stage(root, 3).put("maxPoints", 0));
    assertTrue(zero.contains("Stage 3: The stage must be worth more than 0 points."), zero.toString());
    assertTrue(zero.contains("The stages are worth 90 points in all; they must add up to 100."), zero.toString());
  }

  @Test
  void aChoiceStageHasNoOtherFieldsAndTheOptionsNeedTextAndAnId() {
    List<String> extra = rejected(root -> stage(root, 3).put("awards", 1));
    assertTrue(extra.contains("Stage 3 · awards: is not a field of this part of the file."), extra.toString());
    List<String> noText = rejected(root -> ((ObjectNode) options(stage(root, 3)).get(0)).remove("text"));
    assertTrue(noText.stream().anyMatch(p -> p.contains("options[0].text: must be a non-empty text.")), noText.toString());
    List<String> noOptions = rejected(root -> stage(root, 3).remove("options"));
    assertTrue(noOptions.stream().anyMatch(p -> p.contains("Stage 3 · options: must be a list.")), noOptions.toString());
  }

  @Test
  void aBriefMayHoldLineBreaksAndIndentedLinesAreKept() {
    assertTrue(((Choice) content().stage(3)).head().brief().contains("\n"), "line breaks survive the import");
    String brief = ((SentinelContent.Input) content().stage(6)).head().brief();
    assertTrue(brief.lines().anyMatch(line -> line.startsWith("    Parent A:")), "an indented line keeps its four spaces");
    assertTrue(((SentinelContent.Input) content().stage(9)).head().brief().lines().anyMatch(line -> line.startsWith("    ├─ MIN: 3, 5")));
  }

  // ---- scoring: all or nothing, by option id -------------------------------------------------------

  @Test
  void theRightOptionEarnsTheFullWorthAndAnyOtherEarnsNothing() {
    assertEquals(new BigDecimal("10.00"), points(3, "{\"option\":\"a\"}"));
    for (String wrong : List.of("b", "c", "d")) assertEquals(new BigDecimal("0.00"), points(3, "{\"option\":\"" + wrong + "\"}"), wrong);
  }

  @Test
  void aWorthOtherThanTenIsScoredAsWritten() {
    ObjectNode root = (ObjectNode) JSON.readTree(SentinelFixture.shipped());
    stage(root, 3).put("maxPoints", 12.5);
    stage(root, 4).put("maxPoints", 7.5);
    Stage choice = MissionDocument.load(root).stage(3);
    assertEquals(new BigDecimal("12.50"), SentinelScoring.points(choice, new StageAnswer.Option("a")));
    assertEquals(new BigDecimal("0.00"), SentinelScoring.points(choice, new StageAnswer.Option("c")));
  }

  @Test
  void anOptionThatDoesNotExistOrAWrongShapeIsRefused() {
    Stage stage = content().stage(3);
    assertThrows(IllegalArgumentException.class, () -> SentinelScoring.points(stage, new StageAnswer.Option("z")));
    assertThrows(IllegalArgumentException.class, () -> SentinelScoring.points(stage, new StageAnswer.Option("0")), "a text is not an id");
    assertThrows(IllegalArgumentException.class, () -> SentinelScoring.points(stage, new StageAnswer.Pick("a")), "a configuration is not an option");
    for (String bad : List.of("{}", "[]", "{\"option\":1}", "{\"option\":\"\"}", "{\"option\":\"a\",\"extra\":1}", "{\"config\":\"a\"}", "{\"option\":null}")) {
      assertThrows(IllegalArgumentException.class, () -> StageAnswers.read(stage, JSON.readTree(bad)), bad);
    }
    assertThrows(IllegalArgumentException.class, () -> StageAnswers.read(stage, null));
  }

  @Test
  void aChoiceStageHasNoExperiments() {
    Stage stage = content().stage(3);
    assertFalse(SentinelRuns.hasRuns(stage));
    assertEquals(List.of(), SentinelRuns.configIds(stage));
    assertTrue(SentinelRuns.run(stage, "a").isEmpty());
  }

  @Test
  void aPerfectTeamStillEarns100() {
    BigDecimal total = BigDecimal.ZERO;
    for (int stage = 1; stage <= 10; stage++) total = total.add(points(stage, SentinelFixture.PERFECT.get(stage - 1)));
    assertEquals(0, total.compareTo(BigDecimal.valueOf(100)));
  }

  // ---- the option order ----------------------------------------------------------------------------

  private static final List<ChoiceOption> FOUR =
    List.of(new ChoiceOption("a", "0"), new ChoiceOption("b", "-1.5"), new ChoiceOption("c", "1.5"), new ChoiceOption("d", "5.5"));

  @Test
  void theOrderIsTheSameForTheSameTeamAndStageEveryTime() {
    for (long team = 1; team <= 40; team++) {
      assertEquals(ChoiceOrder.shuffled(FOUR, team, 3), ChoiceOrder.shuffled(FOUR, team, 3), "team " + team);
    }
  }

  @Test
  void theOrderIsAPermutationAndTheInputIsNotChanged() {
    List<ChoiceOption> before = List.copyOf(FOUR);
    for (long team = 1; team <= 40; team++) {
      List<ChoiceOption> order = ChoiceOrder.shuffled(FOUR, team, 6);
      assertEquals(Set.copyOf(FOUR), Set.copyOf(order));
      assertEquals(4, order.size());
    }
    assertEquals(before, FOUR);
  }

  @Test
  void differentTeamsAndStagesGetDifferentOrders() {
    Set<List<String>> orders = new HashSet<>();
    for (long team = 1; team <= 60; team++) orders.add(ChoiceOrder.shuffled(FOUR, team, 3).stream().map(ChoiceOption::id).toList());
    assertTrue(orders.size() >= 12, "60 teams should see many of the 24 possible orders, saw " + orders.size());
    Set<List<String>> stages = new HashSet<>();
    for (int stage : List.of(3, 6, 8, 9)) stages.add(ChoiceOrder.shuffled(FOUR, 7, stage).stream().map(ChoiceOption::id).toList());
    assertTrue(stages.size() >= 2, "one team does not see the same order on every stage");
    assertNotEquals(ChoiceOrder.seed(1, 3), ChoiceOrder.seed(2, 3));
    assertNotEquals(ChoiceOrder.seed(1, 3), ChoiceOrder.seed(1, 4));
  }

  @Test
  void theCorrectOptionIsNotAlwaysFirst() {
    long first = 0;
    for (long team = 1; team <= 60; team++) if (ChoiceOrder.shuffled(FOUR, team, 3).getFirst().id().equals("a")) first++;
    assertTrue(first > 3 && first < 40, "about a quarter of 60 teams, got " + first);
  }

  @Test
  void theOrderIsFixedByTheSeedAndNotByTheJvm() {
    // A fixed team and stage always give this exact order (java.util.Random and Collections.shuffle are specified).
    assertEquals(
      ChoiceOrder.shuffled(FOUR, 12345L, 8).stream().map(ChoiceOption::id).toList(),
      ChoiceOrder.shuffled(List.of(FOUR.get(0), FOUR.get(1), FOUR.get(2), FOUR.get(3)), 12345L, 8).stream().map(ChoiceOption::id).toList()
    );
  }
}
