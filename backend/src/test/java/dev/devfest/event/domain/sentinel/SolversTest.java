package dev.devfest.event.domain.sentinel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.sentinel.AStar.Edge;
import dev.devfest.event.domain.sentinel.AStar.Node;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Each solver of the AI mission room against values worked out by hand. */
class SolversTest {

  // ---- outlier detectors --------------------------------------------------------------------------

  @Test
  void tukeyHingesAreTheMediansOfTheLowerAndUpperHalves() {
    assertArrayEquals(new double[] {2.5, 6.5}, Outliers.hinges(new double[] {8, 1, 5, 2, 4, 3, 7, 6}), 1e-12, "even count: two halves of four");
    assertArrayEquals(new double[] {3, 7}, Outliers.hinges(new double[] {1, 2, 3, 4, 5, 6, 7, 8, 9}), 1e-12, "odd count: the median (5) belongs to both halves");
    assertArrayEquals(new double[] {2.5, 5.5}, Outliers.hinges(new double[] {7, 1, 5, 3, 4, 2, 6}), 1e-12);
    assertArrayEquals(new double[] {4.0, 4.5}, Outliers.hinges(new double[] {4.0, 4.2, 3.9, 4.1, 4.3, 3.8, 4.0, 4.4, 4.1, 3.7, 4.5, 5.6, 7.5, 7.0}), 1e-12, "the east values of the shipped readings");
  }

  @Test
  void fencesReachKTimesTheIqrBeyondTheHinges() {
    double[] fence = Outliers.fences(new double[] {1, 2, 3, 4, 5, 6, 7, 8}, 1.5);
    assertEquals(2.5 - 1.5 * 4, fence[0], 1e-12);
    assertEquals(6.5 + 1.5 * 4, fence[1], 1e-12);
  }

  @Test
  void populationStandardDeviationDividesByN() {
    assertEquals(2.0, Outliers.populationSd(new double[] {2, 4, 4, 4, 5, 5, 7, 9}), 1e-12);
    assertEquals(0.0, Outliers.populationSd(new double[] {3, 3, 3}));
  }

  @Test
  void aPointIsFlaggedWhenItIsOutsideOnXOrOnYAndAConstantAxisFlagsNothing() {
    List<Outliers.Point> points = List.of(
      new Outliers.Point("a", 1, 1), new Outliers.Point("b", 2, 1), new Outliers.Point("c", 3, 1), new Outliers.Point("d", 4, 1),
      new Outliers.Point("e", 5, 1), new Outliers.Point("f", 6, 1), new Outliers.Point("g", 3, 9)
    );
    assertEquals(List.of("g"), Outliers.iqr(points, 1.5), "far out on y only; x alone would flag nothing");
    assertEquals(List.of(), Outliers.zScore(List.of(new Outliers.Point("a", 7, 5), new Outliers.Point("b", 7, 5)), 0.5), "no spread, no flag");
  }

  // ---- k-nearest neighbours -----------------------------------------------------------------------

  private static final List<Knn.Labelled> VEHICLES = List.of(
    new Knn.Labelled("a", "Car", 1, 1),
    new Knn.Labelled("b", "Car", 2, 1),
    new Knn.Labelled("c", "Van", 5, 5),
    new Knn.Labelled("d", "Van", 6, 5),
    new Knn.Labelled("e", "Van", 5, 6),
    new Knn.Labelled("odd", "Car", 5.1, 5.1)
  );

  @Test
  void knnRanksByDistanceAndVotesAmongTheKNearest() {
    List<Knn.Neighbor> ranked = Knn.ranked(VEHICLES, 5, 5);
    assertEquals(List.of("c", "odd", "d", "e", "b", "a"), ranked.stream().map(n -> n.point().id()).toList());
    assertEquals(0.0, ranked.getFirst().distance(), 1e-12);
    assertEquals("Car", Knn.classify(VEHICLES, 5.1, 5.1, 1), "k=1 follows the single nearest point");
    assertEquals("Van", Knn.classify(VEHICLES, 5.1, 5.1, 5), "four vans outvote one car");
  }

  @Test
  void knnReportsTiesInsteadOfGuessing() {
    List<Knn.Labelled> tied = List.of(new Knn.Labelled("a", "Car", 0, 0), new Knn.Labelled("b", "Van", 2, 0), new Knn.Labelled("c", "Van", 0, 2));
    assertFalse(Knn.boundaryTie(tied, 0, 0, 1));
    assertTrue(Knn.boundaryTie(tied, 1, 1, 1), "a and the next point are equally far from (1,1)... both at 1.414");
    assertTrue(Knn.voteTie(List.of(new Knn.Labelled("a", "Car", 0, 0), new Knn.Labelled("b", "Van", 1, 0)), 0.2, 0, 2));
    assertFalse(Knn.voteTie(VEHICLES, 5.1, 5.1, 5));
    assertEquals("Car", Knn.classify(List.of(new Knn.Labelled("a", "Car", 0, 0), new Knn.Labelled("b", "Van", 1, 0)), 0.2, 0, 2), "a tied vote goes to the nearer class");
  }

  // ---- confusion metrics --------------------------------------------------------------------------

  @Test
  void confusionCountsAndMetricsFromTheRows() {
    List<Confusion.Row> rows = new java.util.ArrayList<>();
    for (int i = 0; i < 6; i++) rows.add(new Confusion.Row(true, true));
    for (int i = 0; i < 2; i++) rows.add(new Confusion.Row(false, true));
    for (int i = 0; i < 4; i++) rows.add(new Confusion.Row(true, false));
    for (int i = 0; i < 8; i++) rows.add(new Confusion.Row(false, false));
    Confusion confusion = Confusion.of(rows);
    assertEquals(new Confusion(6, 2, 4, 8), confusion);
    assertEquals(0.75, confusion.precision(), 1e-12);
    assertEquals(0.6, confusion.recall(), 1e-12);
    assertEquals(0, new Confusion(0, 0, 3, 3).precision(), "no positive prediction: precision is 0, never NaN");
    assertEquals(0, new Confusion(0, 3, 0, 3).recall(), "no real positive: recall is 0, never NaN");
    assertEquals("Recall", Confusion.METRIC_FOR_MISSES);
  }

  // ---- K-Means ------------------------------------------------------------------------------------

  @Test
  void kMeansAssignsUpdatesAndStopsWhenNothingMoves() {
    List<KMeans.Point> points = List.of(
      new KMeans.Point("1", 1, 1), new KMeans.Point("2", 2, 1), new KMeans.Point("3", 1, 2), new KMeans.Point("4", 2, 2),
      new KMeans.Point("5", 9, 9), new KMeans.Point("6", 10, 9), new KMeans.Point("7", 9, 10), new KMeans.Point("8", 10, 10)
    );
    KMeans.Result result = KMeans.run(points, List.of(new KMeans.Centre(0, 0), new KMeans.Centre(5, 5)));
    assertEquals(4, result.size(0));
    assertEquals(4, result.size(1));
    assertEquals(1.5, result.centres().get(0).x(), 1e-12);
    assertEquals(1.5, result.centres().get(0).y(), 1e-12);
    assertEquals(9.5, result.centres().get(1).x(), 1e-12);
    assertFalse(result.ambiguous());
    assertFalse(result.emptyCluster());
  }

  @Test
  void kMeansFlagsTiesAndEmptyClusters() {
    List<KMeans.Point> points = List.of(new KMeans.Point("1", 0, 0), new KMeans.Point("2", 4, 0));
    assertFalse(KMeans.run(points, List.of(new KMeans.Centre(1, 0), new KMeans.Centre(3, 0))).ambiguous());
    assertTrue(KMeans.run(List.of(new KMeans.Point("m", 2, 0)), List.of(new KMeans.Centre(1, 0), new KMeans.Centre(3, 0))).ambiguous(), "a point midway is a tie");
    assertFalse(KMeans.run(points, List.of(new KMeans.Centre(1, 0), new KMeans.Centre(3, 0))).emptyCluster());
    assertTrue(KMeans.run(List.of(new KMeans.Point("1", 0, 0), new KMeans.Point("2", 1, 0)), List.of(new KMeans.Centre(0, 0), new KMeans.Centre(500, 500))).emptyCluster());
  }

  // ---- A* -----------------------------------------------------------------------------------------

  /** Lib -> Lab -> Admin -> Garage -> Gate 3 costs 15; greedy by h runs down the Cafeteria detour and pays 47. */
  private static AStar campus() {
    List<Node> nodes = List.of(
      new Node("lib", "Library", 9), new Node("lab", "Lab", 8), new Node("caf", "Cafeteria", 7), new Node("adm", "Admin", 6),
      new Node("dor", "Dorm", 6), new Node("gym", "Gym", 5), new Node("gar", "Garage", 3), new Node("g3", "Gate 3", 0)
    );
    List<Edge> edges = List.of(
      new Edge("lib", "lab", 3), new Edge("lib", "caf", 9), new Edge("lab", "caf", 6), new Edge("lab", "adm", 5), new Edge("lab", "dor", 4),
      new Edge("caf", "gym", 11), new Edge("adm", "dor", 11), new Edge("adm", "gar", 3), new Edge("dor", "gym", 9), new Edge("gar", "g3", 4)
    );
    return new AStar(nodes, edges);
  }

  @Test
  void aStarExpandsByLowestFAndBreaksTiesAlphabetically() {
    AStar.Result result = campus().aStar("lib", "g3");
    // f: Lib 9, Lab 11, Dorm 13, Admin 14, Garage 15, Gate 3 15 (reached last); Cafeteria (16) is never expanded.
    assertEquals(List.of("lib", "lab", "dor", "adm", "gar", "g3"), result.order());
    assertEquals(List.of("lib", "lab", "adm", "gar", "g3"), result.path());
    assertEquals(15, result.cost(), 1e-12);
  }

  @Test
  void greedySearchByHAloneFindsACostlierRoute() {
    AStar.Result greedy = campus().greedy("lib", "g3");
    assertEquals(List.of("lib", "caf", "gym", "dor", "adm", "gar", "g3"), greedy.path());
    assertEquals(47, greedy.cost(), 1e-12);
    assertTrue(greedy.cost() > campus().aStar("lib", "g3").cost());
  }

  @Test
  void aStarChecksAdmissibilityAndUnreachableGoals() {
    Map<String, Double> real = campus().costsTo("g3");
    assertEquals(15, real.get("lib"), 1e-12);
    assertEquals(7, real.get("adm"), 1e-12);
    assertNull(new AStar(List.of(new Node("a", "A", 0), new Node("b", "B", 0)), List.of()).aStar("a", "b"));
  }

  // ---- Naive Bayes --------------------------------------------------------------------------------

  @Test
  void naiveBayesMultipliesThePriorByEveryWordsProbability() {
    Map<String, NaiveBayes.WordOdds> table = Map.of(
      "urgent", new NaiveBayes.WordOdds(0.40, 0.10),
      "files", new NaiveBayes.WordOdds(0.30, 0.25),
      "tonight", new NaiveBayes.WordOdds(0.20, 0.15),
      "meeting", new NaiveBayes.WordOdds(0.05, 0.30)
    );
    NaiveBayes.Verdict verdict = NaiveBayes.classify(0.1, 0.9, List.of("urgent", "files", "tonight", "meeting"), table);
    assertEquals(0.00012, verdict.threat(), 1e-12);
    assertEquals(0.0010125, verdict.normal(), 1e-12);
    assertEquals("Normal", verdict.label(), "frightening words, but the prior and one calm word win");
    assertThrows(IllegalArgumentException.class, () -> NaiveBayes.classify(0.5, 0.5, List.of("nope"), table));
  }

  // ---- forward chaining ---------------------------------------------------------------------------

  @Test
  void forwardChainingFiresRulesUntilNothingIsNew() {
    List<ForwardChaining.Rule> rules = List.of(
      new ForwardChaining.Rule("R3", List.of("b", "c"), "d"),
      new ForwardChaining.Rule("R1", List.of("a"), "b"),
      new ForwardChaining.Rule("R2", List.of("a"), "c"),
      new ForwardChaining.Rule("R4", List.of("d", "z"), "never")
    );
    Set<String> start = new LinkedHashSet<>(List.of("a"));
    assertEquals(List.of("a", "b", "c", "d"), List.copyOf(ForwardChaining.closure(start, rules)));
    assertEquals(List.of("R1", "R2", "R3"), ForwardChaining.firingOrder(start, rules));
    assertFalse(ForwardChaining.applicable(rules.get(0), start), "R3 needs b and c first");
    assertTrue(ForwardChaining.applicable(rules.get(1), start));
    assertFalse(ForwardChaining.closure(start, rules).contains("never"));
  }
}
