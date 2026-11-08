package dev.devfest.event.service;

import dev.devfest.event.domain.RoundPhase;
import dev.devfest.event.domain.RoundTimer;
import dev.devfest.event.domain.sentinel.NaiveBayes;
import dev.devfest.event.domain.sentinel.SentinelContent;
import dev.devfest.event.domain.sentinel.SentinelContent.*;
import dev.devfest.event.domain.sentinel.SentinelContentRules;
import dev.devfest.event.domain.sentinel.SentinelRuns;
import dev.devfest.event.dto.AiRoomActivityView;
import dev.devfest.event.dto.AiRoomActivityView.Result;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.SentinelEvaluationRecord;
import dev.devfest.event.repository.SentinelWorkRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The team's view of the AI room. The only place that turns the organizer's content and the team's records into
 * what a team may see: every field below is listed by hand, so no points, award, flagged list, accuracy, correct
 * flag or derived answer can reach {@link AiRoomActivityView}.
 */
final class AiRoomViews {

  /** Everything the view is built from, read by the activity service. */
  record MemberData(
    EventRoomRecord room,
    RoundTimer timer,
    Instant now,
    Optional<SentinelContent> content,
    List<SentinelWorkRepository.Run> runs,
    List<SentinelWorkRepository.Submission> submissions,
    Optional<SentinelEvaluationRecord> evaluation
  ) {}

  private AiRoomViews() {}

  static AiRoomActivityView member(MemberData data, ObjectMapper json) {
    RoundPhase phase = data.timer().effectivePhase(data.now());
    boolean playing = data.evaluation().isEmpty() && (phase == RoundPhase.RUNNING || phase == RoundPhase.PAUSED);
    SentinelContent content = data.content().orElse(null);
    Set<Integer> saved = data.submissions().stream().map(SentinelWorkRepository.Submission::stage).collect(Collectors.toSet());
    return new AiRoomActivityView(
      data.room().id(),
      data.room().name(),
      phase,
      data.now(),
      content != null,
      content == null ? "" : content.story().start(),
      SentinelContent.STAGE_COUNT,
      saved.size(),
      content != null && playing ? missions(content, data.runs(), saved, json) : List.of(),
      data.evaluation().map(evaluation -> result(evaluation, data.room(), content)).orElse(null)
    );
  }

  /** The clue follows the team's collected clue (an override changes it), never its score. */
  private static Result result(SentinelEvaluationRecord evaluation, EventRoomRecord room, SentinelContent content) {
    if (evaluation.clueCollected()) {
      return new Result(true, room.clueKey(), content == null ? "" : content.story().passed());
    }
    return new Result(false, null, content == null ? "" : content.story().failed());
  }

  private static List<AiRoomActivityView.Mission> missions(SentinelContent content, List<SentinelWorkRepository.Run> runs, Set<Integer> saved, ObjectMapper json) {
    List<AiRoomActivityView.Mission> missions = new ArrayList<>();
    for (Mission mission : content.missions()) {
      List<AiRoomActivityView.Stage> stages = new ArrayList<>();
      for (Stage stage : mission.stages()) stages.add(stage(stage, runs, saved.contains(stage.number()), json));
      missions.add(new AiRoomActivityView.Mission(mission.id(), mission.title(), mission.intro(), stages));
    }
    return missions;
  }

  private static AiRoomActivityView.Stage stage(Stage stage, List<SentinelWorkRepository.Run> runs, boolean saved, ObjectMapper json) {
    Head head = stage.head();
    String type = SentinelContentRules.kind(stage);
    if (saved) return new AiRoomActivityView.Stage(head.number(), type, head.title(), null, null, true, null, List.of(), null);
    List<AiRoomActivityView.Run> own = runs
      .stream()
      .filter(run -> run.stage() == head.number())
      .map(run -> new AiRoomActivityView.Run(run.config(), tree(json, run.resultJson())))
      .toList();
    Integer runsLeft = SentinelRuns.hasRuns(stage) && SentinelRuns.budget(stage) != SentinelRuns.UNLIMITED ? SentinelRuns.budget(stage) - own.size() : null;
    return new AiRoomActivityView.Stage(head.number(), type, head.title(), head.fieldNote(), head.brief(), false, data(stage), own, runsLeft);
  }

  static JsonNode tree(ObjectMapper json, String text) {
    return json.readTree(text);
  }

  /** What each stage draws. Hand-written for every kind: nothing is copied wholesale from the content. */
  static Map<String, Object> data(Stage stage) {
    Map<String, Object> data = new LinkedHashMap<>();
    switch (stage) {
      case OutlierHunt s -> {
        data.put("readings", s.readings());
        data.put("configs", s.configs().stream().map(c -> mapOf("id", c.id(), "method", c.method(), "threshold", c.threshold())).toList());
      }
      case FeatureCheck s -> {
        data.put("target", s.target());
        data.put("features", s.features().stream().map(f -> mapOf("id", f.id(), "label", f.label())).toList());
      }
      case TuneDeploy s -> {
        data.put("runsAllowed", s.maxRuns());
        data.put("configs", s.configs().stream().map(c -> mapOf("id", c.id(), "label", c.label())).toList());
      }
      case NearestVehicle s -> {
        data.put("classes", s.classes());
        data.put("vehicles", s.vehicles());
        data.put("unknown", s.unknown());
        data.put("ks", List.of(1, 5));
      }
      case CameraAccuracy s -> {
        data.put("positive", s.positive());
        data.put("metrics", s.metrics());
        data.put("rows", s.rows());
      }
      case GroupFrames s -> {
        data.put("dots", s.dots());
        data.put("centroids", s.centroids());
        data.put("asked", s.asked());
      }
      case RoutePlanner s -> {
        data.put("places", s.places());
        data.put("roads", s.roads());
        data.put("start", s.start());
        data.put("goal", s.goal());
        data.put("tieBreak", s.tieBreak());
      }
      case ThreatFilter s -> {
        data.put("message", s.message());
        data.put("priors", mapOf("threat", s.priorThreat(), "normal", s.priorNormal()));
        data.put("table", s.table());
        data.put("classes", List.of(NaiveBayes.THREAT, NaiveBayes.NORMAL));
      }
      case RuleEngine s -> {
        data.put("facts", s.facts());
        data.put("known", s.known());
        data.put("rules", s.rules());
        data.put("conclusions", s.conclusions());
      }
      case RagFix s -> {
        data.put("question", s.question());
        data.put("chunkSizes", s.chunkSizes());
        data.put("topKs", s.topKs());
        data.put("runsAllowed", s.maxRuns());
        data.put("configs", s.configs().stream().map(c -> mapOf("id", c.id(), "chunkSize", c.chunkSize(), "topK", c.topK())).toList());
      }
    }
    return data;
  }

  private static Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
    return map;
  }
}
