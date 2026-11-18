package dev.devfest.event.domain.sentinel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.ContentRejectedException;
import dev.devfest.event.domain.sentinel.SentinelContent.*;
import dev.devfest.event.domain.sentinel.StageAnswer.*;
import dev.devfest.event.dto.MissionDocument;
import dev.devfest.event.dto.StageAnswers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The shipped docs/ai-room/sentinel-content.json: it imports cleanly, holds its traps, and every stage scores as designed. */
class SentinelContentTest {

  static final ObjectMapper JSON = JsonMapper.builder().build();
  private static SentinelContent content() {
    return MissionDocument.read(JSON.readTree(SentinelFixture.shipped())).content();
  }

  private static BigDecimal points(int stage, String answerJson) {
    Stage s = content().stage(stage);
    return SentinelScoring.points(s, StageAnswers.read(s, JSON.readTree(answerJson)));
  }

  /** The file as it was stored before the multiple choice stages: the retired kinds still play and score. */
  private static SentinelContent v1() {
    return MissionDocument.load(JSON.readTree(SentinelFixture.v1()));
  }

  private static BigDecimal v1Points(int stage, String answerJson) {
    Stage s = v1().stage(stage);
    return SentinelScoring.points(s, StageAnswers.read(s, JSON.readTree(answerJson)));
  }

  // ---- the file -----------------------------------------------------------------------------------

  @Test
  void theShippedFileImportsCleanlyWithTenStagesWorth100() {
    MissionDocument document = MissionDocument.read(JSON.readTree(SentinelFixture.shipped()));
    assertEquals(60, document.passScore());
    SentinelContent content = document.content();
    assertEquals(0, content.totalPoints().compareTo(BigDecimal.valueOf(100)));
    assertEquals(List.of(3, 3, 4), content.missions().stream().map(m -> m.stages().size()).toList());
    assertEquals(List.of("predict", "see", "noor"), content.missions().stream().map(Mission::id).toList());
    assertEquals(List.of(30, 30, 40), content.missions().stream().map(m -> m.stages().stream().mapToInt(s -> s.head().maxPoints().intValue()).sum()).toList());
    assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), content.stages().stream().map(Stage::number).toList());
    assertTrue(content.story().start().startsWith("SENTINEL OFFLINE."));
    assertEquals("SENTINEL RESTORED. All systems are back online. Recovered record:", content.story().passed());
    assertEquals("SENTINEL UNSTABLE. Some systems could not be restored. Your team did not reach the required score.", content.story().failed());
    for (Stage stage : content.stages()) {
      assertTrue(!stage.head().fieldNote().isBlank() && !stage.head().brief().isBlank(), "stage " + stage.number());
    }
    assertTrue(content.missions().stream().noneMatch(m -> m.intro().isBlank()));
    assertEquals(List.of(), SentinelContentRules.problems(content));
  }

  @Test
  void theFileHoldsNoClue() {
    assertTrue(!SentinelFixture.shipped().toUpperCase().contains("WHITE VAN CLUE") && !SentinelFixture.shipped().contains("clueKey"));
  }

  // ---- the fixture's traps ------------------------------------------------------------------------

  @Test
  void theNearestVehicleIsAnOutlierSoKOneIsWrong() {
    NearestVehicle stage = (NearestVehicle) content().stage(4);
    List<Knn.Labelled> points = stage.vehicles().stream().map(v -> new Knn.Labelled(v.id(), v.kind(), v.length(), v.height())).toList();
    List<Knn.Neighbor> ranked = Knn.ranked(points, stage.unknown().length(), stage.unknown().height());
    assertEquals("v12", ranked.getFirst().point().id());
    assertEquals("Car", Knn.classify(points, stage.unknown().length(), stage.unknown().height(), 1));
    assertEquals("Van", Knn.classify(points, stage.unknown().length(), stage.unknown().height(), 5));
    assertEquals(5, SentinelContentRules.reliableK());
  }

  @Test
  void greedyCostsMoreThanAStarAndTheTieBreakDecidesTheOrder() {
    RoutePlanner stage = (RoutePlanner) content().stage(7);
    AStar graph = SentinelContentRules.graph(stage);
    AStar.Result best = graph.aStar("lib", "g3");
    assertEquals(15, best.cost(), 1e-9);
    assertEquals(47, graph.greedy("lib", "g3").cost(), 1e-9);
    assertEquals(List.of("lib", "caf", "gym", "dor", "adm", "gar", "g3"), graph.greedy("lib", "g3").path());
    assertEquals(List.of("lib", "lab", "dor", "adm", "gar", "g3"), best.order());
    assertFalse(graph.aStarHasFrontierTie("lib", "g3"), "no two places on the frontier share the same f");
    assertTrue(best.order().size() <= 6, "A* expands at most six places");
    assertTrue(stage.tieBreak().toLowerCase().contains("alphabetically"));
    assertEquals("Library", stage.places().stream().filter(p -> p.id().equals(stage.start())).findFirst().orElseThrow().name());
    assertEquals("Gate 3", stage.places().stream().filter(p -> p.id().equals(stage.goal())).findFirst().orElseThrow().name());
  }

  @Test
  void theDerivedAnswersOfTheOtherStagesAreWhatTheDesignSays() {
    Confusion confusion = SentinelContentRules.confusion((CameraAccuracy) content().stage(5));
    assertEquals(new Confusion(6, 2, 4, 8), confusion);
  }

  /** Numbers in a text, in order: the formulas moved to their own lines, none of them changed. */
  private static List<String> numbersIn(String text) {
    List<String> found = new ArrayList<>();
    Matcher m = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(text == null ? "" : text);
    while (m.find()) found.add(m.group());
    return found;
  }

  @Test
  void theFormulaLinesChangeOnlyTheBriefAndFieldNoteTextsAndNoNumber() {
    JsonNode before = JSON.readTree(SentinelFixture.v3());
    JsonNode after = JSON.readTree(SentinelFixture.shipped());
    List<String> differing = new ArrayList<>();
    for (int m = 0; m < before.get("missions").size(); m++) {
      JsonNode oldStages = before.get("missions").get(m).get("stages");
      JsonNode newStages = after.get("missions").get(m).get("stages");
      assertEquals(oldStages.size(), newStages.size());
      for (int i = 0; i < oldStages.size(); i++) {
        ObjectNode oldStage = ((ObjectNode) oldStages.get(i)).deepCopy();
        ObjectNode newStage = ((ObjectNode) newStages.get(i)).deepCopy();
        String number = String.valueOf(oldStage.get("number").intValue());
        for (String text : List.of("brief", "fieldNote")) {
          String was = oldStage.has(text) && !oldStage.get(text).isNull() ? oldStage.get(text).stringValue() : "";
          String now = newStage.has(text) && !newStage.get(text).isNull() ? newStage.get(text).stringValue() : "";
          assertEquals(numbersIn(was), numbersIn(now), "stage " + number + " " + text + ": the numbers");
          // Only line breaks (and the "$$ " marks, the minus sign and the words that now sit on their own line) differ.
          if (!was.equals(now)) differing.add(number + ":" + text);
          oldStage.remove(text);
          newStage.remove(text);
        }
        assertEquals(oldStage, newStage, "stage " + number + ": everything but the two texts is identical");
      }
    }
    assertEquals(List.of("1:fieldNote", "3:brief", "7:brief", "7:fieldNote", "8:brief"), differing);
    for (String text : List.of("$$ Q1 - k x IQR", "$$ Q3 + k x IQR", "$$ z = w1·x1 + w2·x2 + b", "$$ ReLU(z) = max(0, z)", "$$ f = g + h", "$$ Entropy = -Σ p·log2(p)", "$$ log2(0.25) = -2")) {
      assertTrue(SentinelFixture.shipped().contains(text), text);
    }
  }

  @Test
  void aMissionStoredWithMultipleChoiceStagesSixEightAndNineStillLoadsAndScores() {
    SentinelContent stored = MissionDocument.load(JSON.readTree(SentinelFixture.v2()));
    for (int number : List.of(3, 6, 8, 9)) {
      Stage stage = stored.stage(number);
      assertEquals("CHOICE", SentinelContentRules.kind(stage), "stage " + number);
      assertEquals(new BigDecimal("10.00"), SentinelScoring.points(stage, StageAnswers.read(stage, JSON.readTree("{\"option\":\"a\"}"))));
      assertEquals(new BigDecimal("0.00"), SentinelScoring.points(stage, StageAnswers.read(stage, JSON.readTree("{\"option\":\"b\"}"))));
    }
  }

  @Test
  void theRetiredKindsStillDeriveTheirAnswersFromAStoredV1File() {
    GroupFrames groups = (GroupFrames) v1().stage(6);
    KMeans.Result result = SentinelContentRules.clusters(groups);
    assertEquals(6, result.size(SentinelContentRules.askedIndex(groups)));
    assertEquals(7.5, result.centres().get(1).x(), 1e-12);
    assertEquals(6.5, result.centres().get(1).y(), 1e-12);
    assertTrue(result.rounds() >= 3, "the animation has something to show");
    NaiveBayes.Verdict verdict = SentinelContentRules.verdict((ThreatFilter) v1().stage(8));
    assertEquals("Normal", verdict.label());
    assertEquals("insider-involved", SentinelContentRules.conclusion((RuleEngine) v1().stage(9)));
    assertEquals(List.of("TUNE_DEPLOY", "GROUP_FRAMES", "THREAT_FILTER", "RULE_ENGINE"), List.of(3, 6, 8, 9).stream().map(n -> SentinelContentRules.kind(v1().stage(n))).toList());
  }

  // ---- derived data agrees with the design ---------------------------------------------------------

  private static List<String> flagged(String config) {
    OutlierHunt stage = (OutlierHunt) content().stage(1);
    OutlierConfig chosen = stage.configs().stream().filter(c -> c.id().equals(config)).findFirst().orElseThrow();
    return SentinelRuns.flagged(stage, chosen);
  }

  @Test
  void outlierHuntFlagsAreDerivedFromTheReadingsExactlyAsDesigned() {
    assertEquals(List.of("g12", "g13", "g14"), flagged("iqr-1.5"));
    assertEquals(List.of("g13", "g14"), flagged("iqr-3"));
    assertEquals(List.of("g13", "g14"), flagged("z-2"));
    assertEquals(List.of(), flagged("z-3"));
    // The run answers with the derived list, and the file no longer carries one.
    OutlierHunt stage = (OutlierHunt) content().stage(1);
    assertEquals(List.of("g12", "g13", "g14"), SentinelRuns.run(stage, "iqr-1.5").orElseThrow().get("flagged"));
    assertTrue(!SentinelFixture.shipped().contains("\"flagged\""));
  }

  @Test
  void pingTwelveIsOutsideTheOnePointFiveFencesAndInsideTheThreeFencesOnBothAxes() {
    OutlierHunt stage = (OutlierHunt) content().stage(1);
    Reading ping = stage.readings().stream().filter(r -> r.id().equals("g12")).findFirst().orElseThrow();
    assertEquals(5.6, ping.x());
    assertEquals(4.7, ping.y());
    List<Outliers.Point> points = stage.readings().stream().map(r -> new Outliers.Point(r.id(), r.x(), r.y())).toList();
    double[] x15 = Outliers.fences(Outliers.axis(points, true), 1.5);
    double[] y15 = Outliers.fences(Outliers.axis(points, false), 1.5);
    double[] x3 = Outliers.fences(Outliers.axis(points, true), 3);
    double[] y3 = Outliers.fences(Outliers.axis(points, false), 3);
    assertEquals(5.25, x15[1], 1e-9);
    assertEquals(4.35, y15[1], 1e-9);
    assertTrue(ping.x() > x15[1] && ping.y() > y15[1], "outside 1.5 on both axes");
    assertTrue(ping.x() < x3[1] && ping.y() < y3[1], "inside 3 on both axes");
    // Tukey's hinges are the stated method. The inclusive linear-interpolation quartiles (Excel QUARTILE.INC, NumPy,
    // Python statistics "inclusive") agree. The exclusive ones (R type 6, Excel QUARTILE.EXC) pull Q3 towards the
    // ping itself and would keep it inside the 1.5 fences, which is why the Field note and Field Guide name the method.
    for (boolean x : new boolean[] {true, false}) {
      double[] sorted = java.util.Arrays.stream(Outliers.axis(points, x)).sorted().toArray();
      double q1 = percentile(sorted, 0.25, true);
      double q3 = percentile(sorted, 0.75, true);
      double value = x ? ping.x() : ping.y();
      assertTrue(value > q3 + 1.5 * (q3 - q1) && value < q3 + 3 * (q3 - q1), "inclusive quartiles, " + (x ? "x" : "y"));
      double[] exclusive = {percentile(sorted, 0.25, false), percentile(sorted, 0.75, false)};
      assertTrue(value < exclusive[1] + 1.5 * (exclusive[1] - exclusive[0]), "exclusive quartiles keep it inside the 1.5 fences, " + (x ? "x" : "y"));
    }
  }

  /** Linear-interpolation percentile: inclusive (n-1) or exclusive (n+1) positions. */
  private static double percentile(double[] sorted, double share, boolean inclusive) {
    double position = inclusive ? share * (sorted.length - 1) : share * (sorted.length + 1) - 1;
    position = Math.max(0, Math.min(sorted.length - 1, position));
    int low = (int) Math.floor(position);
    int high = (int) Math.ceil(position);
    return sorted[low] + (sorted[high] - sorted[low]) * (position - low);
  }

  @Test
  void aReadingOnAFenceIsRefusedBecauseItsFlagWouldDependOnRounding() {
    List<String> problems = rejected(root -> ((ObjectNode) stage(root, 1).get("readings").get(11)).put("x", 5.25));
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("Stage 1: Configuration \"iqr-1.5\": a reading sits exactly on its fence")), problems.toString());
  }

  @Test
  void everyStagesTextsAndNumbersAgreeWithItsOwnData() {
    SentinelContent content = content();
    OutlierHunt outlier = (OutlierHunt) content.stage(1);
    assertTrue(outlier.head().fieldNote().contains("Tukey's hinges") && outlier.head().fieldNote().contains("population standard deviation"), "stage 1 states its method");
    FeatureCheck features = (FeatureCheck) content.stage(2);
    assertTrue(features.head().brief().contains(features.target()), "stage 2 names its target");
    Choice choice = (Choice) content.stage(3);
    assertEquals(List.of("a", "b", "c", "d"), choice.options().stream().map(ChoiceOption::id).toList());
    assertEquals(List.of("0", "-1.5", "1.5", "5.5"), choice.options().stream().map(ChoiceOption::text).toList());
    assertEquals("a", choice.correct());
    assertTrue(choice.figure() instanceof NeuronFigure, "stage 3 draws its neuron");
    for (int number : List.of(6, 8, 9)) {
      Input input = (Input) content.stage(number);
      assertTrue(input.head().fieldNote().startsWith("Noor's notes:"), "stage " + number);
      assertTrue(input.figure() != null, "stage " + number + " has a figure");
    }
    assertEquals(new TextAnswer(List.of("101001")), ((Input) content.stage(6)).answer());
    assertEquals(new NumberAnswer(0.19, 0.01), ((Input) content.stage(8)).answer());
    assertEquals(new NumberAnswer(4, 0), ((Input) content.stage(9)).answer());
    assertTrue(((Input) content.stage(6)).head().brief().endsWith("Type the final child as 6 bits."));
    assertTrue(((Input) content.stage(8)).head().brief().endsWith("Type the information gain to 2 decimal places."));
    assertTrue(((Input) content.stage(9)).head().brief().endsWith("Type the value."));
    for (Stage stage : content.stages()) assertEquals(0, stage.head().maxPoints().compareTo(BigDecimal.TEN), "every stage is worth 10");
    assertEquals(List.of("Neuron Check", "Patrol Evolution", "Best Split", "Patrol Game"), List.of(3, 6, 8, 9).stream().map(n -> content.stage(n).head().title()).toList());
    NearestVehicle nearest = (NearestVehicle) content.stage(4);
    assertTrue(nearest.head().brief().contains("hollow diamond"), "stage 4 describes the unknown vehicle's mark");
    CameraAccuracy camera = (CameraAccuracy) content.stage(5);
    assertEquals(20, camera.rows().size());
    assertTrue(camera.head().brief().startsWith("Twenty camera frames"), "stage 5 counts its rows");
    RoutePlanner route = (RoutePlanner) content.stage(7);
    String start = route.places().stream().filter(p -> p.id().equals(route.start())).findFirst().orElseThrow().name();
    String goal = route.places().stream().filter(p -> p.id().equals(route.goal())).findFirst().orElseThrow().name();
    assertTrue(route.head().brief().contains("from " + start + " to " + goal), "stage 7 names its start and goal");
    RagFix rag = (RagFix) content.stage(10);
    assertTrue(rag.head().brief().contains("try " + rag.maxRuns() + " different settings"), "stage 10 states its try budget");
    for (String word : List.of("Noor", "last", "confirmed")) {
      assertTrue(rag.question().contains(word) && rag.head().brief().contains(word), "stage 10's question and brief are about the same thing: " + word);
    }
    for (RagConfig config : rag.configs()) {
      assertEquals(config.award().signum() > 0, config.reply().contains("Cybersecurity office"), "a reply names the Cybersecurity office exactly when its setting earns points: " + config.id());
    }
    assertEquals("large-1", rag.configs().stream().filter(c -> c.award().compareTo(rag.head().maxPoints()) == 0).findFirst().orElseThrow().id());
  }

  // ---- scoring ------------------------------------------------------------------------------------

  @Test
  void aPerfectTeamEarns100AndTheConfigurationStagesFollowTheirTables() {
    assertEquals(new BigDecimal("10.00"), points(1, "{\"config\":\"iqr-1.5\"}"));
    assertEquals(new BigDecimal("6.00"), points(1, "{\"config\":\"iqr-3\"}"));
    assertEquals(new BigDecimal("4.00"), points(1, "{\"config\":\"z-2\"}"));
    assertEquals(new BigDecimal("0.00"), points(1, "{\"config\":\"z-3\"}"));
    assertEquals(new BigDecimal("10.00"), points(3, "{\"option\":\"a\"}"));
    assertEquals(new BigDecimal("0.00"), points(3, "{\"option\":\"b\"}"));
    assertEquals(new BigDecimal("0.00"), v1Points(3, "{\"config\":\"knn-1\"}"));
    assertEquals(new BigDecimal("5.00"), v1Points(3, "{\"config\":\"knn-5\"}"));
    assertEquals(new BigDecimal("10.00"), v1Points(3, "{\"config\":\"tree-5\"}"));
    assertEquals(new BigDecimal("0.00"), v1Points(3, "{\"config\":\"tree-20\"}"));
    assertEquals(new BigDecimal("10.00"), points(10, "{\"config\":\"large-1\"}"));
    assertEquals(new BigDecimal("5.00"), points(10, "{\"config\":\"large-3\"}"));
    assertEquals(new BigDecimal("5.00"), points(10, "{\"config\":\"small-3\"}"));
    assertEquals(new BigDecimal("0.00"), points(10, "{\"config\":\"small-1\"}"));
    BigDecimal total = BigDecimal.ZERO;
    for (int stage = 1; stage <= 10; stage++) total = total.add(points(stage, SentinelFixture.PERFECT.get(stage - 1)));
    assertEquals(0, total.compareTo(BigDecimal.valueOf(100)));
  }

  @Test
  void featureCheckIsCorrectOverTheNumberOfFeatures() {
    String oneWrong = SentinelFixture.PERFECT.get(1).replace("\"plate-number\":\"DROP\"", "\"plate-number\":\"USE\"");
    assertEquals(new BigDecimal("8.33"), points(2, oneWrong));
    String allWrong = "{\"choices\":{\"entry-gate\":\"DROP\",\"entry-time\":\"DROP\",\"vehicle-type\":\"DROP\",\"previous-visits\":\"DROP\",\"plate-number\":\"USE\",\"exit-camera-time\":\"USE\"}}";
    assertEquals(new BigDecimal("0.00"), points(2, allWrong));
    assertThrows(IllegalArgumentException.class, () -> points(2, "{\"choices\":{\"entry-gate\":\"USE\"}}"), "every feature must be decided");
  }

  @Test
  void nearestVehicleScoresEachPartAlone() {
    assertEquals(new BigDecimal("7.00"), points(4, "{\"k1\":\"Van\",\"k5\":\"Van\",\"reliable\":5}"), "k=1 wrong costs 3");
    assertEquals(new BigDecimal("6.00"), points(4, "{\"k1\":\"Car\",\"k5\":\"Car\",\"reliable\":5}"), "k=5 wrong costs 4");
    assertEquals(new BigDecimal("7.00"), points(4, "{\"k1\":\"Car\",\"k5\":\"Van\",\"reliable\":1}"), "trusting k=1 costs 3");
    assertThrows(IllegalArgumentException.class, () -> points(4, "{\"k1\":\"Boat\",\"k5\":\"Van\",\"reliable\":5}"));
  }

  @Test
  void cameraAccuracyAcceptsADecimalWithinOneHundredth() {
    String base = "{\"tp\":6,\"fp\":2,\"fn\":4,\"tn\":8,\"precision\":%s,\"recall\":%s,\"metric\":\"%s\"}";
    assertEquals(new BigDecimal("10.00"), points(5, base.formatted("0.76", "0.59", "Recall")), "±0.01 is accepted");
    assertEquals(new BigDecimal("6.00"), points(5, base.formatted("0.77", "0.58", "Recall")), "outside ±0.01 loses precision (2) and recall (2)");
    assertEquals(new BigDecimal("8.00"), points(5, base.formatted("0.75", "0.6", "Precision")), "the wrong metric loses 2");
    assertEquals(new BigDecimal("8.00"), points(5, "{\"tp\":6,\"fp\":3,\"fn\":3,\"tn\":8,\"precision\":0.75,\"recall\":0.6,\"metric\":\"Recall\"}"), "two wrong counts lose 2");
  }

  @Test
  void clusterAnswerNeedsTheSizeAndBothCoordinatesWithinOneHundredth() {
    assertEquals(new BigDecimal("10.00"), v1Points(6, "{\"size\":6,\"x\":7.51,\"y\":6.49}"));
    assertEquals(new BigDecimal("5.00"), v1Points(6, "{\"size\":6,\"x\":7.5,\"y\":6.52}"), "one coordinate off loses the centroid");
    assertEquals(new BigDecimal("5.00"), v1Points(6, "{\"size\":5,\"x\":7.5,\"y\":6.5}"), "wrong size loses 5");
  }

  @Test
  void routePlannerScoresTheOrderAndTheRouteWithItsCostSeparately() {
    String order = "[\"lib\",\"lab\",\"dor\",\"adm\",\"gar\",\"g3\"]";
    String path = "[\"lib\",\"lab\",\"adm\",\"gar\",\"g3\"]";
    assertEquals(new BigDecimal("5.00"), points(7, "{\"order\":%s,\"path\":%s,\"cost\":16}".formatted(order, path)), "the right route with the wrong cost earns nothing for the route");
    assertEquals(new BigDecimal("5.00"), points(7, "{\"order\":[\"lib\",\"lab\",\"adm\",\"gar\",\"g3\"],\"path\":%s,\"cost\":15}".formatted(path)), "an order that skipped a place");
    assertEquals(new BigDecimal("0.00"), points(7, "{\"order\":[\"lib\",\"caf\",\"gym\",\"dor\",\"adm\",\"gar\",\"g3\"],\"path\":[\"lib\",\"caf\",\"gym\",\"dor\",\"adm\",\"gar\",\"g3\"],\"cost\":47}"), "the greedy route");
    assertThrows(IllegalArgumentException.class, () -> points(7, "{\"order\":[\"moon\"],\"path\":[],\"cost\":1}"));
  }

  @Test
  void threatFilterAcceptsOnePercentOfTheTrueValues() {
    String base = "{\"threat\":%s,\"normal\":%s,\"label\":\"%s\"}";
    assertEquals(new BigDecimal("10.00"), v1Points(8, base.formatted("0.0001209", "0.00102", "Normal")), "within 1% of 0.00012 and 0.0010125");
    assertEquals(new BigDecimal("6.00"), v1Points(8, base.formatted("0.000122", "0.00102", "Normal")), "1.7% off loses the threat value");
    assertEquals(new BigDecimal("8.00"), v1Points(8, base.formatted("0.00012", "0.0010125", "Threat")), "the wrong class loses 2");
  }

  @Test
  void ruleEngineIsAllOrNothing() {
    assertEquals(new BigDecimal("10.00"), v1Points(9, "{\"conclusion\":\"insider-involved\"}"));
    assertEquals(new BigDecimal("0.00"), v1Points(9, "{\"conclusion\":\"cover-up\"}"));
    assertThrows(IllegalArgumentException.class, () -> v1Points(9, "{\"conclusion\":\"aliens\"}"));
  }

  @Test
  void answersWithTheWrongShapeAreRefused() {
    for (String bad : List.of("[]", "{}", "{\"config\":5}", "{\"config\":\"iqr-1.5\",\"extra\":1}", "{\"config\":\"nope\"}")) {
      assertThrows(IllegalArgumentException.class, () -> points(1, bad), bad);
    }
    assertThrows(IllegalArgumentException.class, () -> points(5, "{\"tp\":1.5,\"fp\":2,\"fn\":4,\"tn\":8,\"precision\":0.75,\"recall\":0.6,\"metric\":\"Recall\"}"));
    assertThrows(IllegalArgumentException.class, () -> StageAnswers.read(content().stage(1), null));
    assertThrows(IllegalArgumentException.class, () -> SentinelScoring.points(content().stage(1), new Conclusion("x")), "a conclusion is not an answer to stage 1");
  }

  // ---- validation lists every problem -------------------------------------------------------------

  private static List<String> rejected(Consumer<ObjectNode> change) {
    ObjectNode root = (ObjectNode) JSON.readTree(SentinelFixture.shipped());
    change.accept(root);
    return assertThrows(ContentRejectedException.class, () -> MissionDocument.read(root)).problems();
  }

  private static ObjectNode stage(ObjectNode root, int number) {
    int index = number - 1;
    int mission = index < 3 ? 0 : index < 6 ? 1 : 2;
    int inside = index < 3 ? index : index < 6 ? index - 3 : index - 6;
    return (ObjectNode) root.get("missions").get(mission).get("stages").get(inside);
  }

  @Test
  void shapeProblemsAreAllListedTogetherWithTheirPaths() {
    List<String> problems = rejected(root -> {
      ((ObjectNode) root.get("story")).remove("start");
      stage(root, 1).put("maxPoints", "ten");
      ((ObjectNode) stage(root, 1).get("configs").get(0)).put("threshold", "high");
      stage(root, 2).put("surprise", true);
      ((ObjectNode) root.get("missions").get(1)).remove("stages");
      root.put("format", 2);
    });
    assertTrue(problems.contains("format must be 1."), problems.toString());
    assertTrue(problems.contains("story.start: must be a non-empty text."), problems.toString());
    assertTrue(problems.contains("Stage 1 · maxPoints: must be a number."), problems.toString());
    assertTrue(problems.contains("Stage 1 · configs[0].threshold: must be a number."), problems.toString());
    assertTrue(problems.contains("Stage 2 · surprise: is not a field of this part of the file."), problems.toString());
    assertTrue(problems.contains("Mission 2 · stages: must be a list."), problems.toString());
    assertEquals(6, problems.size(), problems.toString());
  }

  @Test
  void contentProblemsAreAllListedTogetherAndNamedByStage() {
    List<String> problems = rejected(root -> {
      stage(root, 1).put("maxPoints", 11);
      ((ObjectNode) stage(root, 4).get("vehicles").get(11)).put("kind", "Van");
      ((ObjectNode) stage(root, 7).get("places").get(1)).put("h", 40);
      stage(root, 3).put("correct", "z");
      ((ObjectNode) stage(root, 5).get("awards")).put("tp", 5);
      ((ObjectNode) root.get("story")).put("failed", "You scored 59.");
      ((ObjectNode) stage(root, 10).get("configs").get(0)).put("award", 11);
    });
    assertTrue(problems.contains("The stages are worth 101 points in all; they must add up to 100."), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("Stage 1: One configuration must earn the stage's full worth.")), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("Stage 4: The nearest vehicle must be an outlier")), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("Stage 7: The estimate of \"lab\" (40.0) is larger than its real cost")), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("Stage 3: correct must be the id of one of the options.")), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("Stage 5: The awards add up to 14 but the stage is worth 10.")), problems.toString());
    assertTrue(problems.contains("story.failed must not contain a number."), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("Stage 10: Every configuration's award must be between 0 and the stage's worth.")), problems.toString());
  }

  @Test
  void trapsThatAreRemovedAreRefused() {
    List<String> noTrap = rejected(root -> {
      // Gate 3 straight from the Library: greedy search now pays the same as A*.
      ((ObjectNode) stage(root, 7).get("roads").get(0)).put("to", "g3").put("cost", 15);
    });
    assertTrue(noTrap.stream().anyMatch(p -> p.startsWith("Stage 7: Greedy search by estimate alone must find a costlier route than A*")), noTrap.toString());
    List<String> badCorrect = rejected(root -> stage(root, 3).put("correct", "q"));
    assertEquals(List.of("Stage 3: correct must be the id of one of the options."), badCorrect);
    List<String> misnumbered = rejected(root -> ((ObjectNode) root.get("missions").get(0).get("stages").get(1)).put("number", 5));
    assertTrue(misnumbered.contains("Stage 5: type FEATURE_CHECK belongs to stage 2, not stage 5."), misnumbered.toString());
  }

  @Test
  void anEmptyOrNonObjectFileIsRefused() {
    assertEquals(List.of("The file must be a JSON object."), assertThrows(ContentRejectedException.class, () -> MissionDocument.read(JSON.readTree("[]"))).problems());
    JsonNode tree = JSON.readTree("{}");
    assertTrue(assertThrows(ContentRejectedException.class, () -> MissionDocument.read(tree)).problems().size() >= 3);
  }
}
