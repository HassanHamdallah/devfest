package dev.devfest.event.domain.sentinel;

import dev.devfest.event.domain.sentinel.SentinelContent.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Everything a valid mission file must satisfy beyond its shape (rules §19.2). Every problem found is listed, none
 * stops the others, and each names the stage. Pure: the derived answers are computed with the same solvers that
 * score the teams, which is how the fixture's traps are held (the nearest neighbour is an outlier, greedy search
 * pays more than A*, ...).
 */
public final class SentinelContentRules {

  /** The kind of every stage, fixed by its number. */
  public static final List<String> STAGE_KINDS = List.of(
    "OUTLIER_HUNT", "FEATURE_CHECK", "TUNE_DEPLOY", "NEAREST_VEHICLE", "CAMERA_ACCURACY",
    "GROUP_FRAMES", "ROUTE_PLANNER", "THREAT_FILTER", "RULE_ENGINE", "RAG_FIX"
  );
  public static final List<String> MISSION_IDS = List.of("predict", "see", "noor");
  /** How many stages each mission holds, in order. */
  public static final List<Integer> MISSION_SIZES = List.of(3, 3, 4);

  public static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,39}");
  public static final double EPS = 1e-9;

  private SentinelContentRules() {}

  public static String kind(Stage stage) {
    return STAGE_KINDS.get(stage.number() - 1);
  }

  public static List<String> problems(SentinelContent content) {
    List<String> problems = new ArrayList<>();
    story(content.story(), problems);
    structure(content, problems);
    BigDecimal total = BigDecimal.ZERO;
    for (Stage stage : content.stages()) {
      String where = "Stage " + stage.number() + ": ";
      total = total.add(stage.head().maxPoints());
      List<String> own = new ArrayList<>();
      switch (stage) {
        case OutlierHunt s -> outlier(s, own);
        case FeatureCheck s -> features(s, own);
        case TuneDeploy s -> tune(s, own);
        case NearestVehicle s -> nearest(s, own);
        case CameraAccuracy s -> camera(s, own);
        case GroupFrames s -> groups(s, own);
        case RoutePlanner s -> route(s, own);
        case ThreatFilter s -> threat(s, own);
        case RuleEngine s -> rules(s, own);
        case RagFix s -> rag(s, own);
      }
      own.forEach(problem -> problems.add(where + problem));
    }
    if (total.compareTo(BigDecimal.valueOf(SentinelContent.MAX_POINTS)) != 0) {
      problems.add("The stages are worth " + total.stripTrailingZeros().toPlainString() + " points in all; they must add up to 100.");
    }
    return problems;
  }

  // ---- story and structure ------------------------------------------------------------------------

  private static void story(Story story, List<String> problems) {
    if (hasDigit(story.passed())) problems.add("story.passed must not contain a number.");
    if (hasDigit(story.failed())) problems.add("story.failed must not contain a number.");
  }

  private static boolean hasDigit(String text) {
    return text.chars().anyMatch(Character::isDigit);
  }

  private static void structure(SentinelContent content, List<String> problems) {
    if (content.missions().size() != MISSION_IDS.size()) {
      problems.add("The file needs exactly 3 missions (predict, see, noor).");
      return;
    }
    int next = 1;
    for (int m = 0; m < content.missions().size(); m++) {
      Mission mission = content.missions().get(m);
      if (!MISSION_IDS.get(m).equals(mission.id())) problems.add("Mission " + (m + 1) + " must have the id \"" + MISSION_IDS.get(m) + "\".");
      if (mission.stages().size() != MISSION_SIZES.get(m)) {
        problems.add("Mission \"" + mission.id() + "\" needs exactly " + MISSION_SIZES.get(m) + " stages.");
      }
      for (Stage stage : mission.stages()) {
        if (stage.number() != next) problems.add("Mission \"" + mission.id() + "\": stage " + stage.number() + " is out of order (expected stage " + next + ").");
        next++;
      }
    }
  }

  // ---- shared helpers -----------------------------------------------------------------------------

  private static void ids(String what, List<String> ids, List<String> problems) {
    Set<String> seen = new HashSet<>();
    for (String id : ids) {
      if (!ID.matcher(id).matches()) problems.add(what + " id \"" + id + "\" must be 1 to 40 letters, digits, - _ or .");
      else if (!seen.add(id)) problems.add("Two " + what + "s share the id \"" + id + "\".");
    }
  }

  private static void awardsSum(Head head, Map<String, BigDecimal> awards, List<String> expectedKeys, List<String> problems) {
    for (String key : expectedKeys) {
      if (!awards.containsKey(key)) problems.add("awards." + key + " is missing.");
    }
    for (String key : awards.keySet()) {
      if (!expectedKeys.contains(key)) problems.add("awards." + key + " is not used by this stage.");
    }
    BigDecimal sum = awards.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    if (awards.values().stream().anyMatch(value -> value.signum() < 0)) problems.add("Awards cannot be negative.");
    if (sum.compareTo(head.maxPoints()) != 0) {
      problems.add("The awards add up to " + sum.stripTrailingZeros().toPlainString() + " but the stage is worth " + head.maxPoints().stripTrailingZeros().toPlainString() + ".");
    }
  }

  /** Per-configuration awards: each within the stage's worth, and one configuration earns all of it. */
  private static void configAwards(Head head, List<BigDecimal> awards, List<String> problems) {
    if (awards.stream().anyMatch(award -> award.signum() < 0 || award.compareTo(head.maxPoints()) > 0)) {
      problems.add("Every configuration's award must be between 0 and the stage's worth.");
    }
    if (awards.stream().noneMatch(award -> award.compareTo(head.maxPoints()) == 0)) {
      problems.add("One configuration must earn the stage's full worth.");
    }
  }

  private static boolean positive(Head head) {
    return head.maxPoints().signum() > 0;
  }

  // ---- PREDICT ------------------------------------------------------------------------------------

  private static void outlier(OutlierHunt stage, List<String> problems) {
    if (!positive(stage.head())) problems.add("The stage must be worth more than 0 points.");
    if (stage.readings().size() < 5) problems.add("Add at least 5 readings.");
    if (stage.configs().size() < 2) problems.add("Add at least 2 configurations.");
    ids("reading", stage.readings().stream().map(Reading::id).toList(), problems);
    ids("configuration", stage.configs().stream().map(OutlierConfig::id).toList(), problems);
    for (OutlierConfig config : stage.configs()) {
      if (!List.of("IQR", "Z").contains(config.method())) problems.add("Configuration \"" + config.id() + "\": method must be IQR or Z.");
      if (!(config.threshold() > 0)) problems.add("Configuration \"" + config.id() + "\": the threshold must be above 0.");
    }
    if (problems.isEmpty()) outlierMargins(stage, problems);
    Set<String> pairs = new HashSet<>();
    for (OutlierConfig config : stage.configs()) {
      if (!pairs.add(config.method() + ":" + config.threshold())) problems.add("Two configurations use " + config.method() + " " + config.threshold() + ".");
    }
    configAwards(stage.head(), stage.configs().stream().map(OutlierConfig::award).toList(), problems);
  }

  /**
   * The flagged readings are derived, so they must be unambiguous: every axis needs a spread (IQR above 0, standard
   * deviation above 0) and no reading may sit on a fence or on a Z-score threshold (a different rounding would flag it).
   */
  private static void outlierMargins(OutlierHunt stage, List<String> problems) {
    List<Outliers.Point> points = stage.readings().stream().map(r -> new Outliers.Point(r.id(), r.x(), r.y())).toList();
    for (boolean x : new boolean[] {true, false}) {
      double[] values = Outliers.axis(points, x);
      double[] hinges = Outliers.hinges(values);
      if (hinges[1] - hinges[0] <= 0) problems.add("The readings have no interquartile spread on the " + (x ? "x" : "y") + " axis.");
      if (Outliers.populationSd(values) <= 0) problems.add("The readings have no spread on the " + (x ? "x" : "y") + " axis.");
    }
    if (!problems.isEmpty()) return;
    for (OutlierConfig config : stage.configs()) {
      boolean iqr = "IQR".equals(config.method());
      if (Outliers.margin(points, iqr, config.threshold()) < 1e-6) {
        problems.add("Configuration \"" + config.id() + "\": a reading sits exactly on its " + (iqr ? "fence" : "threshold") + "; move it so the flagged readings are unambiguous.");
      }
    }
  }

  private static void features(FeatureCheck stage, List<String> problems) {
    if (!positive(stage.head())) problems.add("The stage must be worth more than 0 points.");
    if (stage.features().size() < 2 || stage.features().size() > 12) problems.add("Add 2 to 12 features.");
    ids("feature", stage.features().stream().map(Feature::id).toList(), problems);
    if (stage.features().stream().noneMatch(Feature::use)) problems.add("At least one feature must be a feature to use.");
    if (stage.features().stream().allMatch(Feature::use)) problems.add("At least one feature must be a feature to drop.");
  }

  private static void tune(TuneDeploy stage, List<String> problems) {
    if (!positive(stage.head())) problems.add("The stage must be worth more than 0 points.");
    if (stage.configs().size() < 2) problems.add("Add at least 2 configurations.");
    ids("configuration", stage.configs().stream().map(ModelConfig::id).toList(), problems);
    if (stage.maxRuns() < 1 || stage.maxRuns() > stage.configs().size()) problems.add("maxRuns must be between 1 and the number of configurations.");
    for (ModelConfig config : stage.configs()) {
      if (config.train() < 0 || config.train() > 100 || config.validation() < 0 || config.validation() > 100) {
        problems.add("Configuration \"" + config.id() + "\": accuracies must be between 0 and 100.");
      }
    }
    configAwards(stage.head(), stage.configs().stream().map(ModelConfig::award).toList(), problems);
  }

  // ---- SEE ----------------------------------------------------------------------------------------

  private static List<Knn.Labelled> labelled(NearestVehicle stage) {
    return stage.vehicles().stream().map(v -> new Knn.Labelled(v.id(), v.kind(), v.length(), v.height())).toList();
  }

  private static void nearest(NearestVehicle stage, List<String> problems) {
    awardsSum(stage.head(), stage.awards(), List.of("k1", "k5", "reliable"), problems);
    ids("vehicle", stage.vehicles().stream().map(Vehicle::id).toList(), problems);
    if (stage.vehicles().size() < 8) problems.add("Add at least 8 labelled vehicles.");
    if (stage.classes().size() < 2 || new HashSet<>(stage.classes()).size() != stage.classes().size()) problems.add("List at least 2 different classes.");
    if (!stage.classes().containsAll(stage.vehicles().stream().map(Vehicle::kind).toList())) problems.add("A vehicle has a class that is not in the list of classes.");
    if (!problems.isEmpty()) return;
    List<Knn.Labelled> points = labelled(stage);
    double x = stage.unknown().length();
    double y = stage.unknown().height();
    for (int k : List.of(1, 5)) {
      if (Knn.boundaryTie(points, x, y, k)) problems.add("The " + k + "-th and next neighbours are equally far; the neighbours of k=" + k + " would be ambiguous.");
      if (Knn.voteTie(points, x, y, k)) problems.add("The vote at k=" + k + " is tied.");
    }
    if (problems.isEmpty() && Knn.classify(points, x, y, 1).equals(Knn.classify(points, x, y, 5))) {
      problems.add("The nearest vehicle must be an outlier: k=1 and k=5 must give different classes.");
    }
  }

  /** The derived answers of stage 4: the class at k=1 and k=5, and the more reliable k (the larger, since k=1 is one voice). */
  public static int reliableK() {
    return 5;
  }

  private static void camera(CameraAccuracy stage, List<String> problems) {
    awardsSum(stage.head(), stage.awards(), List.of("tp", "fp", "fn", "tn", "precision", "recall", "metric"), problems);
    if (stage.rows().size() < 10 || stage.rows().size() > 60) problems.add("Add 10 to 60 rows.");
    if (stage.metrics().size() < 2 || new HashSet<>(stage.metrics()).size() != stage.metrics().size()) problems.add("List at least 2 different metrics.");
    if (!stage.metrics().contains(Confusion.METRIC_FOR_MISSES)) problems.add("The metrics must include \"" + Confusion.METRIC_FOR_MISSES + "\".");
    if (!problems.isEmpty()) return;
    Confusion confusion = confusion(stage);
    if (confusion.tp() == 0 || confusion.fp() == 0 || confusion.fn() == 0 || confusion.tn() == 0) {
      problems.add("The rows must contain at least one true positive, false positive, false negative and true negative.");
    } else if (Math.abs(confusion.precision() - confusion.recall()) <= 0.02) {
      problems.add("Precision and recall must differ by more than 0.02, so each answer can be told apart.");
    }
  }

  public static Confusion confusion(CameraAccuracy stage) {
    return Confusion.of(stage.rows().stream().map(frame -> new Confusion.Row(frame.actual(), frame.predicted())).toList());
  }

  public static KMeans.Result clusters(GroupFrames stage) {
    return KMeans.run(
      stage.dots().stream().map(dot -> new KMeans.Point(dot.id(), dot.x(), dot.y())).toList(),
      stage.centroids().stream().map(c -> new KMeans.Centre(c.x(), c.y())).toList()
    );
  }

  private static void groups(GroupFrames stage, List<String> problems) {
    awardsSum(stage.head(), stage.awards(), List.of("size", "centre"), problems);
    ids("frame", stage.dots().stream().map(Dot::id).toList(), problems);
    ids("centroid", stage.centroids().stream().map(Centroid::id).toList(), problems);
    if (stage.dots().size() < 8 || stage.dots().size() > 40) problems.add("Add 8 to 40 frames.");
    if (stage.centroids().size() != 2) problems.add("Exactly 2 starting centroids are needed.");
    if (stage.centroids().stream().noneMatch(c -> c.id().equals(stage.asked()))) problems.add("asked must be the id of one of the centroids.");
    if (!problems.isEmpty()) return;
    KMeans.Result result = clusters(stage);
    if (result.ambiguous()) problems.add("A frame is equally far from both centroids at some step; its cluster would be ambiguous.");
    if (result.emptyCluster()) problems.add("A cluster becomes empty.");
    if (result.rounds() >= 100) problems.add("K-Means does not settle.");
    if (!problems.isEmpty()) return;
    int asked = askedIndex(stage);
    KMeans.Centre centre = result.centres().get(asked);
    if (!clean(centre.x()) || !clean(centre.y())) {
      problems.add("The final centroid of the asked cluster is not a clean value (" + centre.x() + ", " + centre.y() + "); use at most 2 decimals.");
    }
  }

  public static int askedIndex(GroupFrames stage) {
    for (int i = 0; i < stage.centroids().size(); i++) if (stage.centroids().get(i).id().equals(stage.asked())) return i;
    return 0;
  }

  private static boolean clean(double value) {
    return Math.abs(value * 100 - Math.rint(value * 100)) < 1e-6;
  }

  // ---- NOOR ---------------------------------------------------------------------------------------

  public static AStar graph(RoutePlanner stage) {
    return new AStar(
      stage.places().stream().map(p -> new AStar.Node(p.id(), p.name(), p.h())).toList(),
      stage.roads().stream().map(r -> new AStar.Edge(r.from(), r.to(), r.cost())).toList()
    );
  }

  private static void route(RoutePlanner stage, List<String> problems) {
    awardsSum(stage.head(), stage.awards(), List.of("order", "route"), problems);
    ids("place", stage.places().stream().map(Place::id).toList(), problems);
    Set<String> places = stage.places().stream().map(Place::id).collect(Collectors.toSet());
    if (stage.places().size() < 5) problems.add("Add at least 5 places.");
    if (new HashSet<>(stage.places().stream().map(Place::name).toList()).size() != stage.places().size()) problems.add("Two places share a name.");
    if (!places.contains(stage.start()) || !places.contains(stage.goal()) || stage.start().equals(stage.goal())) problems.add("start and goal must be two different places.");
    if (stage.tieBreak().isBlank()) problems.add("State the tie-break rule.");
    for (Road road : stage.roads()) {
      if (!places.contains(road.from()) || !places.contains(road.to()) || road.from().equals(road.to())) problems.add("A road joins places that do not exist (or a place to itself).");
      if (!(road.cost() > 0)) problems.add("Every road needs a cost above 0.");
    }
    for (Place place : stage.places()) if (place.h() < 0) problems.add("The estimate of \"" + place.id() + "\" cannot be negative.");
    if (!problems.isEmpty()) return;
    AStar graph = graph(stage);
    AStar.Result best = graph.aStar(stage.start(), stage.goal());
    if (best == null) {
      problems.add("The goal cannot be reached from the start.");
      return;
    }
    Map<String, Double> truth = graph.costsTo(stage.goal());
    for (Place place : stage.places()) {
      Double real = truth.get(place.id());
      if (real != null && place.h() > real + EPS) problems.add("The estimate of \"" + place.id() + "\" (" + place.h() + ") is larger than its real cost (" + real + "); A* would not be optimal.");
    }
    for (AStar.Edge edge : graph.directedEdges()) {
      if (graph.node(edge.from()).h() > edge.cost() + graph.node(edge.to()).h() + EPS) {
        problems.add("The estimates are not consistent across the road " + edge.from() + " - " + edge.to() + ".");
        break;
      }
    }
    if (!problems.isEmpty()) return;
    AStar.Result greedy = graph.greedy(stage.start(), stage.goal());
    if (greedy == null || greedy.cost() <= best.cost() + EPS) {
      problems.add("Greedy search by estimate alone must find a costlier route than A* (it finds " + (greedy == null ? "none" : greedy.cost()) + ", A* finds " + best.cost() + ").");
    }
  }

  public static NaiveBayes.Verdict verdict(ThreatFilter stage) {
    Map<String, NaiveBayes.WordOdds> table = stage.table().stream().collect(Collectors.toMap(WordOdds::word, w -> new NaiveBayes.WordOdds(w.threat(), w.normal()), (a, b) -> a));
    return NaiveBayes.classify(stage.priorThreat(), stage.priorNormal(), stage.message(), table);
  }

  private static void threat(ThreatFilter stage, List<String> problems) {
    awardsSum(stage.head(), stage.awards(), List.of("threat", "normal", "label"), problems);
    if (stage.message().size() < 2 || stage.message().size() > 12) problems.add("The message needs 2 to 12 words.");
    Set<String> words = stage.table().stream().map(WordOdds::word).collect(Collectors.toSet());
    if (words.size() != stage.table().size()) problems.add("A word appears twice in the table.");
    if (!words.containsAll(stage.message())) problems.add("Every word of the message must be in the table.");
    for (WordOdds odds : stage.table()) {
      if (!(odds.threat() > 0 && odds.threat() <= 1 && odds.normal() > 0 && odds.normal() <= 1)) problems.add("The probabilities of \"" + odds.word() + "\" must be above 0 and at most 1.");
    }
    boolean priorsOk = stage.priorThreat() > 0 && stage.priorNormal() > 0 && Math.abs(stage.priorThreat() + stage.priorNormal() - 1) < 0.001;
    if (!priorsOk) problems.add("The two priors must be above 0 and add up to 1.");
    if (!problems.isEmpty()) return;
    NaiveBayes.Verdict verdict = verdict(stage);
    double larger = Math.max(verdict.threat(), verdict.normal());
    if (Math.abs(verdict.threat() - verdict.normal()) < 0.05 * larger) problems.add("The two values are within 5% of each other; the class would be unclear.");
  }

  public static ForwardChaining.Rule rule(Rule rule) {
    return new ForwardChaining.Rule(rule.id(), rule.ifAll(), rule.then());
  }

  public static Set<String> closure(RuleEngine stage) {
    return ForwardChaining.closure(new java.util.LinkedHashSet<>(stage.known()), stage.rules().stream().map(SentinelContentRules::rule).toList());
  }

  /** The one offered conclusion that the rules prove; null when the file is invalid. */
  public static String conclusion(RuleEngine stage) {
    Set<String> closure = closure(stage);
    List<String> reached = stage.conclusions().stream().filter(closure::contains).toList();
    return reached.size() == 1 ? reached.getFirst() : null;
  }

  private static void rules(RuleEngine stage, List<String> problems) {
    awardsSum(stage.head(), stage.awards(), List.of("conclusion"), problems);
    ids("fact", stage.facts().stream().map(Fact::id).toList(), problems);
    ids("rule", stage.rules().stream().map(Rule::id).toList(), problems);
    Set<String> facts = stage.facts().stream().map(Fact::id).collect(Collectors.toSet());
    if (!facts.containsAll(stage.known())) problems.add("A starting fact does not exist.");
    if (stage.known().isEmpty()) problems.add("Give at least one starting fact.");
    if (stage.rules().size() < 3) problems.add("Add at least 3 rules.");
    for (Rule rule : stage.rules()) {
      if (rule.ifAll().isEmpty() || !facts.containsAll(rule.ifAll()) || !facts.contains(rule.then()) || rule.ifAll().contains(rule.then())) {
        problems.add("Rule \"" + rule.id() + "\" needs conditions and a result that are existing facts, and its result cannot be one of its conditions.");
      }
    }
    if (stage.conclusions().size() < 2 || !facts.containsAll(stage.conclusions()) || new HashSet<>(stage.conclusions()).size() != stage.conclusions().size()) {
      problems.add("Offer at least 2 different conclusions, all existing facts.");
    }
    if (!problems.isEmpty()) return;
    Set<String> closure = closure(stage);
    long reached = stage.conclusions().stream().filter(closure::contains).count();
    if (reached != 1) problems.add("Exactly one offered conclusion must follow from the rules (" + reached + " do).");
    else if (stage.known().contains(conclusion(stage))) problems.add("The final conclusion cannot be a starting fact.");
  }

  private static void rag(RagFix stage, List<String> problems) {
    if (!positive(stage.head())) problems.add("The stage must be worth more than 0 points.");
    if (stage.question().isBlank()) problems.add("Write the question.");
    if (stage.chunkSizes().size() < 2 || new HashSet<>(stage.chunkSizes()).size() != stage.chunkSizes().size()) problems.add("List at least 2 different chunk sizes.");
    if (stage.topKs().size() < 2 || new HashSet<>(stage.topKs()).size() != stage.topKs().size() || stage.topKs().stream().anyMatch(k -> k < 1)) {
      problems.add("List at least 2 different top-k values, each at least 1.");
    }
    ids("configuration", stage.configs().stream().map(RagConfig::id).toList(), problems);
    if (!problems.isEmpty()) return;
    Set<String> combinations = new HashSet<>();
    for (RagConfig config : stage.configs()) {
      if (!stage.chunkSizes().contains(config.chunkSize()) || !stage.topKs().contains(config.topK())) {
        problems.add("Configuration \"" + config.id() + "\" uses a chunk size or top-k that is not listed.");
      } else if (!combinations.add(config.chunkSize() + "/" + config.topK())) {
        problems.add("Two configurations use " + config.chunkSize() + " with top-k " + config.topK() + ".");
      }
      if (config.chunks().size() != config.topK() || config.chunks().stream().anyMatch(String::isBlank)) {
        problems.add("Configuration \"" + config.id() + "\" must retrieve exactly " + config.topK() + " non-empty chunks.");
      }
      if (config.reply().isBlank()) problems.add("Configuration \"" + config.id() + "\" needs NOOR's reply.");
    }
    if (combinations.size() != stage.chunkSizes().size() * stage.topKs().size()) problems.add("There must be one configuration for every chunk size and top-k pair.");
    if (stage.maxRuns() < 1 || stage.maxRuns() > stage.configs().size()) problems.add("maxRuns must be between 1 and the number of configurations.");
    configAwards(stage.head(), stage.configs().stream().map(RagConfig::award).toList(), problems);
  }
}
