package dev.devfest.event.service;

import dev.devfest.event.domain.RoundPhase;
import dev.devfest.event.domain.RoundTimer;
import dev.devfest.event.domain.sentinel.ChoiceOrder;
import dev.devfest.event.domain.sentinel.InputAnswers;
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
    long teamId,
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
      content != null && playing ? missions(content, data.teamId(), data.runs(), saved, json) : List.of(),
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

  private static List<AiRoomActivityView.Mission> missions(SentinelContent content, long teamId, List<SentinelWorkRepository.Run> runs, Set<Integer> saved, ObjectMapper json) {
    List<AiRoomActivityView.Mission> missions = new ArrayList<>();
    for (Mission mission : content.missions()) {
      List<AiRoomActivityView.Stage> stages = new ArrayList<>();
      for (Stage stage : mission.stages()) stages.add(stage(stage, teamId, runs, saved.contains(stage.number()), json));
      missions.add(new AiRoomActivityView.Mission(mission.id(), mission.title(), mission.intro(), stages));
    }
    return missions;
  }

  private static AiRoomActivityView.Stage stage(Stage stage, long teamId, List<SentinelWorkRepository.Run> runs, boolean saved, ObjectMapper json) {
    Head head = stage.head();
    String type = SentinelContentRules.kind(stage);
    if (saved) return new AiRoomActivityView.Stage(head.number(), type, head.title(), null, null, true, null, List.of(), null);
    List<AiRoomActivityView.Run> own = runs
      .stream()
      .filter(run -> run.stage() == head.number())
      .map(run -> new AiRoomActivityView.Run(run.config(), tree(json, run.resultJson())))
      .toList();
    Integer runsLeft = SentinelRuns.hasRuns(stage) && SentinelRuns.budget(stage) != SentinelRuns.UNLIMITED ? SentinelRuns.budget(stage) - own.size() : null;
    return new AiRoomActivityView.Stage(head.number(), type, head.title(), head.fieldNote(), head.brief(), false, data(stage, teamId), own, runsLeft);
  }

  static JsonNode tree(ObjectMapper json, String text) {
    return json.readTree(text);
  }

  /**
   * What each stage draws. Hand-written for every kind: nothing is copied wholesale from the content. A multiple
   * choice stage lists its options in this team's own order and never says which one is correct.
   */
  static Map<String, Object> data(Stage stage, long teamId) {
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
      case Choice s -> {
        data.put("options", ChoiceOrder.shuffled(s.options(), teamId, s.number()).stream().map(o -> mapOf("id", o.id(), "text", o.text())).toList());
        if (s.figure() != null) data.put("figure", figure(s.figure()));
      }
      case Input s -> {
        // Only how to type it and the drawing: never the accepted values, the number or the tolerance.
        data.put("inputKind", s.answer() instanceof NumberAnswer ? "NUMBER" : "TEXT");
        data.put("maxLength", InputAnswers.MAX_LENGTH);
        if (s.figure() != null) data.put("figure", figure(s.figure()));
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

  /** A figure as the screen draws it: the question's own data, written field by field (there is no answer to leave out). */
  static Map<String, Object> figure(Figure figure) {
    return switch (figure) {
      case NeuronFigure f -> mapOf("type", "neuron", "inputs", f.inputs().stream().map(i -> mapOf("label", i.label(), "value", i.value())).toList(), "weights", f.weights(), "bias", f.bias());
      case CrossoverFigure f -> mapOf("type", "crossover", "parentA", f.parentA(), "parentB", f.parentB(), "cut", f.cut(), "mutate", f.mutate());
      case SplitFigure f -> mapOf(
        "type", "split",
        "parent", mapOf("normal", f.parent().normal(), "suspicious", f.parent().suspicious()),
        "question", f.question(),
        "yes", mapOf("normal", f.yes().normal(), "suspicious", f.yes().suspicious()),
        "no", mapOf("normal", f.no().normal(), "suspicious", f.no().suspicious())
      );
      case MinimaxFigure f -> mapOf("type", "minimax", "root", f.root(), "children", f.children());
    };
  }

  private static Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
    return map;
  }
}
