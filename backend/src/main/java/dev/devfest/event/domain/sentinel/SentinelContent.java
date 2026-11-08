package dev.devfest.event.domain.sentinel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The AI mission room's content (rules §19.2), already read from the import file: a story, three missions and ten
 * stages whose order and kinds are fixed. Everything a stage needs, including each scoring table, lives here;
 * answers that are derived (nearest neighbours, confusion counts, K-Means, A*, Naive Bayes, forward chaining) are
 * never stored, the server computes them from the stage's data.
 */
public record SentinelContent(Story story, List<Mission> missions) {

  public static final int STAGE_COUNT = 10;
  public static final int MAX_POINTS = 100;
  public static final int DEFAULT_PASS_SCORE = 60;
  public static final int FORMAT = 1;

  /** The three texts a team reads: before, and after (restored / unstable, never with a number). */
  public record Story(String start, String passed, String failed) {}

  public record Mission(String id, String title, String intro, List<Stage> stages) {}

  /** What every stage has. {@code maxPoints}: the stage's worth in the room's 100. */
  public record Head(int number, String title, String fieldNote, String brief, BigDecimal maxPoints) {}

  /** A stage; {@link #awards()} is the stage's scoring table and never leaves the server. */
  public sealed interface Stage permits OutlierHunt, FeatureCheck, TuneDeploy, NearestVehicle, CameraAccuracy, GroupFrames, RoutePlanner, ThreatFilter, RuleEngine, RagFix {
    Head head();

    default int number() {
      return head().number();
    }
  }

  // ---- PREDICT ----------------------------------------------------------------------------------

  public record Reading(String id, String label, double x, double y) {}

  /**
   * {@code method}: IQR or Z; {@code award}: points for submitting it. The readings it flags are derived from the
   * readings (see {@link Outliers}), never written in the file.
   */
  public record OutlierConfig(String id, String method, double threshold, BigDecimal award) {}

  public record OutlierHunt(Head head, List<Reading> readings, List<OutlierConfig> configs) implements Stage {}

  public record Feature(String id, String label, boolean use) {}

  public record FeatureCheck(Head head, String target, List<Feature> features) implements Stage {}

  public record ModelConfig(String id, String label, double train, double validation, BigDecimal award) {}

  public record TuneDeploy(Head head, int maxRuns, List<ModelConfig> configs) implements Stage {}

  // ---- SEE --------------------------------------------------------------------------------------

  public record Vehicle(String id, String label, String kind, double length, double height) {}

  public record Spot(double length, double height) {}

  /** {@code awards}: k1, k5, reliable. */
  public record NearestVehicle(Head head, List<Vehicle> vehicles, Spot unknown, List<String> classes, Map<String, BigDecimal> awards) implements Stage {}

  public record Frame(boolean actual, boolean predicted) {}

  /** {@code awards}: tp, fp, fn, tn, precision, recall, metric. */
  public record CameraAccuracy(Head head, String positive, List<Frame> rows, List<String> metrics, Map<String, BigDecimal> awards) implements Stage {}

  public record Dot(String id, double x, double y) {}

  public record Centroid(String id, String label, double x, double y) {}

  /** {@code asked}: the centroid whose cluster is asked about; {@code awards}: size, centre. */
  public record GroupFrames(Head head, List<Dot> dots, List<Centroid> centroids, String asked, Map<String, BigDecimal> awards) implements Stage {}

  // ---- NOOR -------------------------------------------------------------------------------------

  public record Place(String id, String name, double x, double y, double h) {}

  public record Road(String from, String to, double cost) {}

  /** {@code tieBreak}: the rule shown on screen; {@code awards}: order, route. */
  public record RoutePlanner(Head head, List<Place> places, List<Road> roads, String start, String goal, String tieBreak, Map<String, BigDecimal> awards) implements Stage {}

  public record WordOdds(String word, double threat, double normal) {}

  /** {@code awards}: threat, normal, label. */
  public record ThreatFilter(
    Head head,
    List<String> message,
    double priorThreat,
    double priorNormal,
    List<WordOdds> table,
    Map<String, BigDecimal> awards
  ) implements Stage {}

  public record Fact(String id, String text) {}

  public record Rule(String id, List<String> ifAll, String then) {}

  /** {@code known}: the starting facts; {@code conclusions}: the facts offered as the final conclusion; {@code awards}: conclusion. */
  public record RuleEngine(Head head, List<Fact> facts, List<String> known, List<Rule> rules, List<String> conclusions, Map<String, BigDecimal> awards) implements Stage {}

  public record RagConfig(String id, String chunkSize, int topK, List<String> chunks, String reply, BigDecimal award) {}

  public record RagFix(Head head, String question, List<String> chunkSizes, List<Integer> topKs, int maxRuns, List<RagConfig> configs) implements Stage {}

  // ---- lookups ----------------------------------------------------------------------------------

  public List<Stage> stages() {
    return missions.stream().flatMap(mission -> mission.stages().stream()).toList();
  }

  public Stage stage(int number) {
    return stages().stream().filter(stage -> stage.number() == number).findFirst().orElse(null);
  }

  /** The room's total worth: the sum of the stages' maximum points (100 for a valid file). */
  public BigDecimal totalPoints() {
    return stages().stream().map(stage -> stage.head().maxPoints()).reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
