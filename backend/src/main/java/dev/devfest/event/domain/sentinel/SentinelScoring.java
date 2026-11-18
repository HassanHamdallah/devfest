package dev.devfest.event.domain.sentinel;

import dev.devfest.event.domain.sentinel.SentinelContent.*;
import dev.devfest.event.domain.sentinel.StageAnswer.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The points a stage answer earns (rules §19.6). Pure. Derived answers are computed here from the stage's own data,
 * so an author cannot mistype them. An answer that does not fit the stage (an id that does not exist, the wrong
 * kind of answer) is refused with a message for the team.
 */
public final class SentinelScoring {

  /** Counts must match exactly; decimals within 0.01; the two Bayes values within 1% of the true value. */
  public static final double DECIMAL_TOLERANCE = 0.01;
  public static final double RELATIVE_TOLERANCE = 0.01;
  private static final double COST_TOLERANCE = 1e-6;

  private SentinelScoring() {}

  public static BigDecimal points(Stage stage, StageAnswer answer) {
    BigDecimal points = switch (stage) {
      case OutlierHunt s -> pick(answer, s.configs().stream().collect(Collectors.toMap(OutlierConfig::id, OutlierConfig::award)));
      case FeatureCheck s -> features(s, answer);
      case TuneDeploy s -> pick(answer, s.configs().stream().collect(Collectors.toMap(ModelConfig::id, ModelConfig::award)));
      case NearestVehicle s -> nearest(s, answer);
      case CameraAccuracy s -> camera(s, answer);
      case GroupFrames s -> groups(s, answer);
      case RoutePlanner s -> route(s, answer);
      case ThreatFilter s -> threat(s, answer);
      case RuleEngine s -> conclusion(s, answer);
      case RagFix s -> pick(answer, s.configs().stream().collect(Collectors.toMap(RagConfig::id, RagConfig::award)));
      case Choice s -> choice(s, answer);
      case Input s -> input(s, answer);
    };
    return points.setScale(2, RoundingMode.HALF_UP);
  }

  private static <T extends StageAnswer> T as(Class<T> type, StageAnswer answer) {
    if (!type.isInstance(answer)) throw new IllegalArgumentException("This answer does not fit the stage.");
    return type.cast(answer);
  }

  private static BigDecimal pick(StageAnswer answer, Map<String, BigDecimal> awards) {
    Pick pick = as(Pick.class, answer);
    BigDecimal award = awards.get(pick.config());
    if (award == null) throw new IllegalArgumentException("Choose one of the stage's configurations.");
    return award;
  }

  /**
   * A typed answer stage is all or nothing. An answer that cannot be judged (empty, longer than 40 characters, or not a
   * number in a NUMBER stage) is refused with a message for the team, so it never counts as the stage's one answer.
   */
  private static BigDecimal input(Input stage, StageAnswer answer) {
    String typed = as(Typed.class, answer).text();
    InputAnswers.refusal(stage.answer(), typed).ifPresent(message -> {
      throw new IllegalArgumentException(message);
    });
    return InputAnswers.correct(stage.answer(), typed) ? stage.head().maxPoints() : BigDecimal.ZERO;
  }

  /** A multiple choice stage is all or nothing: the stage's full worth for the right option, 0 for any other. */
  private static BigDecimal choice(Choice stage, StageAnswer answer) {
    Option given = as(Option.class, answer);
    if (stage.options().stream().noneMatch(option -> option.id().equals(given.option()))) {
      throw new IllegalArgumentException("Choose one of the options.");
    }
    return given.option().equals(stage.correct()) ? stage.head().maxPoints() : BigDecimal.ZERO;
  }

  private static BigDecimal features(FeatureCheck stage, StageAnswer answer) {
    Map<String, Boolean> use = as(Features.class, answer).use();
    Set<String> ids = stage.features().stream().map(Feature::id).collect(Collectors.toSet());
    if (!use.keySet().equals(ids)) throw new IllegalArgumentException("Decide every feature: use it or drop it.");
    long right = stage.features().stream().filter(feature -> use.get(feature.id()) == feature.use()).count();
    return stage.head().maxPoints().multiply(BigDecimal.valueOf(right)).divide(BigDecimal.valueOf(stage.features().size()), 4, RoundingMode.HALF_UP);
  }

  private static BigDecimal nearest(NearestVehicle stage, StageAnswer answer) {
    Neighbours given = as(Neighbours.class, answer);
    for (String kind : List.of(given.k1(), given.k5())) {
      if (!stage.classes().contains(kind)) throw new IllegalArgumentException("Choose one of the listed classes.");
    }
    if (given.reliable() != 1 && given.reliable() != 5) throw new IllegalArgumentException("Choose k = 1 or k = 5.");
    List<Knn.Labelled> points = stage.vehicles().stream().map(v -> new Knn.Labelled(v.id(), v.kind(), v.length(), v.height())).toList();
    double x = stage.unknown().length();
    double y = stage.unknown().height();
    BigDecimal total = BigDecimal.ZERO;
    if (given.k1().equals(Knn.classify(points, x, y, 1))) total = total.add(stage.awards().get("k1"));
    if (given.k5().equals(Knn.classify(points, x, y, 5))) total = total.add(stage.awards().get("k5"));
    if (given.reliable() == SentinelContentRules.reliableK()) total = total.add(stage.awards().get("reliable"));
    return total;
  }

  private static BigDecimal camera(CameraAccuracy stage, StageAnswer answer) {
    Matrix given = as(Matrix.class, answer);
    if (given.tp() < 0 || given.fp() < 0 || given.fn() < 0 || given.tn() < 0) throw new IllegalArgumentException("Counts cannot be negative.");
    if (!stage.metrics().contains(given.metric())) throw new IllegalArgumentException("Choose one of the listed metrics.");
    Confusion truth = SentinelContentRules.confusion(stage);
    Map<String, BigDecimal> awards = stage.awards();
    BigDecimal total = BigDecimal.ZERO;
    if (given.tp() == truth.tp()) total = total.add(awards.get("tp"));
    if (given.fp() == truth.fp()) total = total.add(awards.get("fp"));
    if (given.fn() == truth.fn()) total = total.add(awards.get("fn"));
    if (given.tn() == truth.tn()) total = total.add(awards.get("tn"));
    if (near(given.precision(), truth.precision())) total = total.add(awards.get("precision"));
    if (near(given.recall(), truth.recall())) total = total.add(awards.get("recall"));
    if (given.metric().equals(Confusion.METRIC_FOR_MISSES)) total = total.add(awards.get("metric"));
    return total;
  }

  private static BigDecimal groups(GroupFrames stage, StageAnswer answer) {
    Cluster given = as(Cluster.class, answer);
    KMeans.Result result = SentinelContentRules.clusters(stage);
    int asked = SentinelContentRules.askedIndex(stage);
    BigDecimal total = BigDecimal.ZERO;
    if (given.size() == result.size(asked)) total = total.add(stage.awards().get("size"));
    KMeans.Centre centre = result.centres().get(asked);
    if (near(given.x(), centre.x()) && near(given.y(), centre.y())) total = total.add(stage.awards().get("centre"));
    return total;
  }

  private static BigDecimal route(RoutePlanner stage, StageAnswer answer) {
    Route given = as(Route.class, answer);
    Set<String> ids = stage.places().stream().map(Place::id).collect(Collectors.toSet());
    if (!ids.containsAll(given.order()) || !ids.containsAll(given.path())) throw new IllegalArgumentException("Use the places shown on the map.");
    AStar.Result truth = SentinelContentRules.graph(stage).aStar(stage.start(), stage.goal());
    BigDecimal total = BigDecimal.ZERO;
    if (given.order().equals(truth.order())) total = total.add(stage.awards().get("order"));
    if (given.path().equals(truth.path()) && Math.abs(given.cost() - truth.cost()) <= COST_TOLERANCE) total = total.add(stage.awards().get("route"));
    return total;
  }

  private static BigDecimal threat(ThreatFilter stage, StageAnswer answer) {
    Bayes given = as(Bayes.class, answer);
    if (!List.of(NaiveBayes.THREAT, NaiveBayes.NORMAL).contains(given.label())) throw new IllegalArgumentException("Choose Threat or Normal.");
    NaiveBayes.Verdict truth = SentinelContentRules.verdict(stage);
    BigDecimal total = BigDecimal.ZERO;
    if (within(given.threat(), truth.threat())) total = total.add(stage.awards().get("threat"));
    if (within(given.normal(), truth.normal())) total = total.add(stage.awards().get("normal"));
    if (given.label().equals(truth.label())) total = total.add(stage.awards().get("label"));
    return total;
  }

  private static BigDecimal conclusion(RuleEngine stage, StageAnswer answer) {
    Conclusion given = as(Conclusion.class, answer);
    if (!stage.conclusions().contains(given.fact())) throw new IllegalArgumentException("Choose one of the listed conclusions.");
    return given.fact().equals(SentinelContentRules.conclusion(stage)) ? stage.awards().get("conclusion") : BigDecimal.ZERO;
  }

  /** Absolute tolerance 0.01 (precision, recall, centroid coordinates). */
  public static boolean near(double given, double truth) {
    return Math.abs(given - truth) <= DECIMAL_TOLERANCE + 1e-12;
  }

  /** Relative tolerance 1% of the true value (the Naive Bayes products, which can be tiny). */
  public static boolean within(double given, double truth) {
    return Math.abs(given - truth) <= RELATIVE_TOLERANCE * Math.abs(truth);
  }
}
