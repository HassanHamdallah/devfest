package dev.devfest.event.domain.sentinel;

import dev.devfest.event.domain.sentinel.SentinelContent.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The experiments of the three stages that have them (1 Outlier Hunt, 3 Tune and Deploy, 10 RAG Fix). A run shows
 * the result of one configuration; the server returns it one run at a time, never the whole table. Pure.
 */
public final class SentinelRuns {

  /** What a run returns when a stage has no limit on distinct configurations. */
  public static final int UNLIMITED = Integer.MAX_VALUE;

  private SentinelRuns() {}

  public static boolean hasRuns(Stage stage) {
    return stage instanceof OutlierHunt || stage instanceof TuneDeploy || stage instanceof RagFix;
  }

  /** How many different configurations a team may run; repeating one is always free. */
  public static int budget(Stage stage) {
    return switch (stage) {
      case TuneDeploy s -> s.maxRuns();
      case RagFix s -> s.maxRuns();
      default -> UNLIMITED;
    };
  }

  /** The ids a team may pick or run in this stage. */
  public static List<String> configIds(Stage stage) {
    return switch (stage) {
      case OutlierHunt s -> s.configs().stream().map(OutlierConfig::id).toList();
      case TuneDeploy s -> s.configs().stream().map(ModelConfig::id).toList();
      case RagFix s -> s.configs().stream().map(RagConfig::id).toList();
      default -> List.of();
    };
  }

  /** The readings a detector flags, derived from the stage's readings (IQR fences or Z-score, per axis). */
  public static List<String> flagged(OutlierHunt stage, OutlierConfig config) {
    List<Outliers.Point> points = stage.readings().stream().map(r -> new Outliers.Point(r.id(), r.x(), r.y())).toList();
    return "IQR".equals(config.method()) ? Outliers.iqr(points, config.threshold()) : Outliers.zScore(points, config.threshold());
  }

  /** The result of running {@code configId}, or empty when the stage has no such configuration. */
  public static Optional<Map<String, Object>> run(Stage stage, String configId) {
    Map<String, Object> result = new LinkedHashMap<>();
    switch (stage) {
      case OutlierHunt s -> {
        Optional<OutlierConfig> config = s.configs().stream().filter(c -> c.id().equals(configId)).findFirst();
        if (config.isEmpty()) return Optional.empty();
        result.put("flagged", flagged(s, config.get()));
      }
      case TuneDeploy s -> {
        Optional<ModelConfig> config = s.configs().stream().filter(c -> c.id().equals(configId)).findFirst();
        if (config.isEmpty()) return Optional.empty();
        result.put("train", config.get().train());
        result.put("validation", config.get().validation());
      }
      case RagFix s -> {
        Optional<RagConfig> config = s.configs().stream().filter(c -> c.id().equals(configId)).findFirst();
        if (config.isEmpty()) return Optional.empty();
        result.put("chunks", config.get().chunks());
        result.put("reply", config.get().reply());
      }
      default -> {
        return Optional.empty();
      }
    }
    return Optional.of(result);
  }
}
