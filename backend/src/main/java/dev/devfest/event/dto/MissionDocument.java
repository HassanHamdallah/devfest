package dev.devfest.event.dto;

import dev.devfest.event.domain.ContentRejectedException;
import dev.devfest.event.domain.sentinel.SentinelContent;
import dev.devfest.event.domain.sentinel.SentinelContent.*;
import dev.devfest.event.domain.sentinel.SentinelContentRules;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The mission import file (rules §19.2): {@code format}, an optional {@code passScore}, the {@code story} and three
 * {@code missions} of ten stages. {@link #read} turns a JSON tree into {@link SentinelContent} and refuses the whole
 * file, listing every problem at once, when its shape is wrong or its content breaks a rule. The file is also the
 * export format (organizers only: it holds the scoring tables).
 */
public record MissionDocument(Integer passScore, SentinelContent content) {

  private static final int TITLE = 120;
  private static final int NOTE = 600;
  private static final int BRIEF = 2000;
  private static final int STORY = 1200;
  private static final int LABEL = 160;

  /** Reads and checks the whole file; nothing is returned for a file with any problem. */
  public static MissionDocument read(JsonNode root) {
    return read(root, true);
  }

  /** Reads a file that was checked when it was stored (no content rules again). */
  public static SentinelContent load(JsonNode stored) {
    return read(stored, false).content();
  }

  private static MissionDocument read(JsonNode root, boolean check) {
    Problems problems = new Problems();
    Fields file = new Fields(root, "", problems);
    file.only("format", "passScore", "story", "missions");
    if (!file.node.isObject()) throw new ContentRejectedException(List.of("The file must be a JSON object."));
    if (!file.node.path("format").isNumber() || file.node.path("format").intValue() != SentinelContent.FORMAT) {
      problems.add("", "format must be " + SentinelContent.FORMAT + ".");
    }
    Integer passScore = null;
    if (file.node.has("passScore")) {
      passScore = file.integer("passScore");
      if (passScore < 0 || passScore > 100) problems.add("passScore", "must be between 0 and 100.");
    }
    Story story = story(file.object("story"));
    List<Mission> missions = new ArrayList<>();
    List<Fields> missionFields = file.objects("missions", 3, 3);
    for (Fields mission : missionFields) missions.add(mission(mission, check));
    if (!problems.isEmpty()) throw new ContentRejectedException(problems.list());
    SentinelContent content = new SentinelContent(story, missions);
    List<String> rules = check ? SentinelContentRules.problems(content) : List.of();
    if (!rules.isEmpty()) throw new ContentRejectedException(rules);
    return new MissionDocument(passScore, content);
  }

  // ---- parts --------------------------------------------------------------------------------------

  private static Story story(Fields story) {
    story.only("start", "passed", "failed");
    return new Story(story.text("start", STORY), story.text("passed", STORY), story.text("failed", STORY));
  }

  private static Mission mission(Fields mission, boolean check) {
    mission.only("id", "title", "intro", "stages");
    String id = mission.text("id", 20);
    String title = mission.text("title", TITLE);
    String intro = mission.text("intro", NOTE);
    List<Stage> stages = new ArrayList<>();
    for (Fields stage : mission.objects("stages", 1, 6)) stages.add(stage(stage, check));
    return new Mission(id, title, intro, stages);
  }

  /** "Stage 3" from the stage's own number; a file with a missing or wrong number is named by its place instead. */
  private static String stageLabel(Fields f) {
    JsonNode number = f.node.path("number");
    if (number.isIntegralNumber() && number.canConvertToInt() && number.intValue() >= 1 && number.intValue() <= SentinelContent.STAGE_COUNT) {
      return "Stage " + number.intValue();
    }
    Matcher at = Pattern.compile("missions\\[(\\d+)]\\.stages\\[(\\d+)]").matcher(f.path);
    return at.matches() ? "Mission " + (Integer.parseInt(at.group(1)) + 1) + ", stage " + (Integer.parseInt(at.group(2)) + 1) : f.path;
  }

  private static Head head(Fields f) {
    return new Head(f.integer("number"), f.text("title", TITLE), f.text("fieldNote", NOTE), f.text("brief", BRIEF), f.decimal("maxPoints"));
  }

  /**
   * A new file may use the kinds of {@link SentinelContentRules#STAGE_KINDS} only, each at its own stage; a file that
   * was stored before ({@code check} false) may also hold the kinds that no stage uses any more.
   */
  private static Stage stage(Fields f, boolean check) {
    f.problems.label(f.path, stageLabel(f));
    String type = f.text("type", 30);
    Head head = head(f);
    List<String> kinds = SentinelContentRules.STAGE_KINDS;
    boolean known = kinds.contains(type) || (!check && (SentinelContentRules.LEGACY_KINDS.contains(type) || "CHOICE".equals(type)));
    if (!known) {
      f.problems.add(f.path, "type must be one of " + kinds.stream().distinct().toList() + ".");
      return null;
    }
    if (check) {
      int index = kinds.indexOf(type);
      boolean once = index == kinds.lastIndexOf(type);
      boolean inRange = head.number() >= 1 && head.number() <= kinds.size();
      if (once && head.number() != index + 1) {
        f.problems.add(f.path, "type " + type + " belongs to stage " + (index + 1) + ", not stage " + head.number() + ".");
      } else if (!once && (!inRange || !kinds.get(head.number() - 1).equals(type))) {
        f.problems.add(f.path, "type " + type + " cannot be stage " + head.number() + (inRange ? " (stage " + head.number() + " is " + kinds.get(head.number() - 1) + ")." : "."));
      }
    }
    return switch (type) {
      case "CHOICE" -> choice(f, head);
      case "INPUT" -> input(f, head);
      case "OUTLIER_HUNT" -> outlier(f, head);
      case "FEATURE_CHECK" -> features(f, head);
      case "TUNE_DEPLOY" -> tune(f, head);
      case "NEAREST_VEHICLE" -> nearest(f, head);
      case "CAMERA_ACCURACY" -> camera(f, head);
      case "GROUP_FRAMES" -> groups(f, head);
      case "ROUTE_PLANNER" -> route(f, head);
      case "THREAT_FILTER" -> threat(f, head);
      case "RULE_ENGINE" -> rules(f, head);
      default -> rag(f, head);
    };
  }

  private static Stage outlier(Fields f, Head head) {
    f.only("number", "type", "title", "fieldNote", "brief", "maxPoints", "readings", "configs");
    List<Reading> readings = new ArrayList<>();
    for (Fields r : f.objects("readings", 1, 200)) {
      r.only("id", "label", "x", "y");
      readings.add(new Reading(r.text("id", 40), r.text("label", LABEL), r.num("x"), r.num("y")));
    }
    List<OutlierConfig> configs = new ArrayList<>();
    for (Fields c : f.objects("configs", 1, 40)) {
      c.only("id", "method", "threshold", "award");
      configs.add(new OutlierConfig(c.text("id", 40), c.text("method", 8), c.num("threshold"), c.decimal("award")));
    }
    return new OutlierHunt(head, readings, configs);
  }

  private static Stage choice(Fields f, Head head) {
    f.only("number", "type", "title", "fieldNote", "brief", "maxPoints", "options", "correct", "figure");
    List<ChoiceOption> options = new ArrayList<>();
    for (Fields o : f.objects("options", 1, 12)) {
      o.only("id", "text");
      options.add(new ChoiceOption(o.text("id", 40), o.text("text", LABEL)));
    }
    return new Choice(head, options, f.text("correct", 40), figure(f));
  }

  private static Stage input(Fields f, Head head) {
    f.only("number", "type", "title", "fieldNote", "brief", "maxPoints", "figure", "answer");
    return new Input(head, figure(f), answer(f.object("answer")));
  }

  /** How a typed answer is judged: {"kind":"TEXT","accepted":[...]} or {"kind":"NUMBER","value":n,"tolerance":t}. */
  private static Answer answer(Fields a) {
    String kind = a.text("kind", 10);
    return switch (kind) {
      case "TEXT" -> {
        a.only("kind", "accepted");
        yield new TextAnswer(a.strings("accepted", 1, 10));
      }
      case "NUMBER" -> {
        a.only("kind", "value", "tolerance");
        yield new NumberAnswer(a.num("value"), a.num("tolerance"));
      }
      default -> {
        if (!kind.isEmpty()) a.problems.add(a.path, "kind must be TEXT or NUMBER.");
        yield new TextAnswer(List.of());
      }
    };
  }

  /** The optional drawing of a stage; null when the stage has none (or its type is not one of the four). */
  private static Figure figure(Fields stage) {
    if (!stage.node.has("figure")) return null;
    Fields f = stage.object("figure");
    if (!f.node.isObject()) return null;
    String type = f.text("type", 20);
    return switch (type) {
      case "neuron" -> {
        f.only("type", "inputs", "weights", "bias");
        List<NeuronInput> inputs = new ArrayList<>();
        for (Fields i : f.objects("inputs", 1, 6)) {
          i.only("label", "value");
          inputs.add(new NeuronInput(i.text("label", 12), i.num("value")));
        }
        yield new NeuronFigure(inputs, f.numbers("weights", 1, 6), f.num("bias"));
      }
      case "crossover" -> {
        f.only("type", "parentA", "parentB", "cut", "mutate");
        yield new CrossoverFigure(f.text("parentA", 40), f.text("parentB", 40), f.integer("cut"), f.integer("mutate"));
      }
      case "split" -> {
        f.only("type", "parent", "question", "yes", "no");
        yield new SplitFigure(counts(f.object("parent")), f.text("question", LABEL), counts(f.object("yes")), counts(f.object("no")));
      }
      case "minimax" -> {
        f.only("type", "root", "children");
        List<List<Double>> children = new ArrayList<>();
        JsonNode list = f.node.path("children");
        if (!list.isArray()) f.problems.add(f.path + ".children", "must be a list of lists of numbers.");
        else {
          for (JsonNode child : list) {
            List<Double> leaves = new ArrayList<>();
            if (!child.isArray()) f.problems.add(f.path + ".children", "must be a list of lists of numbers.");
            else {
              for (JsonNode leaf : child) {
                if (leaf.isNumber()) leaves.add(leaf.doubleValue());
                else f.problems.add(f.path + ".children", "must hold numbers only.");
              }
            }
            children.add(leaves);
          }
        }
        yield new MinimaxFigure(f.text("root", 8), children);
      }
      default -> {
        if (!type.isEmpty()) f.problems.add(f.path, "type must be one of neuron, crossover, split, minimax.");
        yield null;
      }
    };
  }

  private static Counts counts(Fields c) {
    c.only("normal", "suspicious");
    return new Counts(c.integer("normal"), c.integer("suspicious"));
  }

  private static Stage features(Fields f, Head head) {
    f.only("number", "type", "title", "fieldNote", "brief", "maxPoints", "target", "features");
    String target = f.text("target", LABEL);
    List<Feature> features = new ArrayList<>();
    for (Fields feature : f.objects("features", 1, 30)) {
      feature.only("id", "label", "role");
      String role = feature.text("role", 8);
      if (!Set.of("USE", "DROP").contains(role)) feature.problems.add(feature.path, "role must be USE or DROP.");
      features.add(new Feature(feature.text("id", 40), feature.text("label", LABEL), "USE".equals(role)));
    }
    return new FeatureCheck(head, target, features);
  }

  private static Stage tune(Fields f, Head head) {
    f.only("number", "type", "title", "fieldNote", "brief", "maxPoints", "maxRuns", "configs");
    int maxRuns = f.integer("maxRuns");
    List<ModelConfig> configs = new ArrayList<>();
    for (Fields c : f.objects("configs", 1, 40)) {
      c.only("id", "label", "train", "validation", "award");
      configs.add(new ModelConfig(c.text("id", 40), c.text("label", LABEL), c.num("train"), c.num("validation"), c.decimal("award")));
    }
    return new TuneDeploy(head, maxRuns, configs);
  }

  private static Stage nearest(Fields f, Head head) {
    f.only("number", "type", "title", "fieldNote", "brief", "maxPoints", "classes", "vehicles", "unknown", "awards");
    List<String> classes = f.strings("classes", 1, 12);
    List<Vehicle> vehicles = new ArrayList<>();
    for (Fields v : f.objects("vehicles", 1, 100)) {
      v.only("id", "label", "kind", "length", "height");
      vehicles.add(new Vehicle(v.text("id", 40), v.text("label", LABEL), v.text("kind", 40), v.num("length"), v.num("height")));
    }
    Fields unknown = f.object("unknown");
    unknown.only("length", "height");
    return new NearestVehicle(head, vehicles, new Spot(unknown.num("length"), unknown.num("height")), classes, f.awards("awards"));
  }

  private static Stage camera(Fields f, Head head) {
    f.only("number", "type", "title", "fieldNote", "brief", "maxPoints", "positive", "metrics", "rows", "awards");
    String positive = f.text("positive", LABEL);
    List<String> metrics = f.strings("metrics", 1, 12);
    List<Frame> rows = new ArrayList<>();
    for (Fields r : f.objects("rows", 1, 100)) {
      r.only("actual", "predicted");
      rows.add(new Frame(r.bool("actual"), r.bool("predicted")));
    }
    return new CameraAccuracy(head, positive, rows, metrics, f.awards("awards"));
  }

  private static Stage groups(Fields f, Head head) {
    f.only("number", "type", "title", "fieldNote", "brief", "maxPoints", "dots", "centroids", "asked", "awards");
    List<Dot> dots = new ArrayList<>();
    for (Fields d : f.objects("dots", 1, 100)) {
      d.only("id", "x", "y");
      dots.add(new Dot(d.text("id", 40), d.num("x"), d.num("y")));
    }
    List<Centroid> centroids = new ArrayList<>();
    for (Fields c : f.objects("centroids", 1, 8)) {
      c.only("id", "label", "x", "y");
      centroids.add(new Centroid(c.text("id", 40), c.text("label", LABEL), c.num("x"), c.num("y")));
    }
    return new GroupFrames(head, dots, centroids, f.text("asked", 40), f.awards("awards"));
  }

  private static Stage route(Fields f, Head head) {
    f.only("number", "type", "title", "fieldNote", "brief", "maxPoints", "places", "roads", "start", "goal", "tieBreak", "awards");
    List<Place> places = new ArrayList<>();
    for (Fields p : f.objects("places", 1, 60)) {
      p.only("id", "name", "x", "y", "h");
      places.add(new Place(p.text("id", 40), p.text("name", LABEL), p.num("x"), p.num("y"), p.num("h")));
    }
    List<Road> roads = new ArrayList<>();
    for (Fields r : f.objects("roads", 1, 200)) {
      r.only("from", "to", "cost");
      roads.add(new Road(r.text("from", 40), r.text("to", 40), r.num("cost")));
    }
    return new RoutePlanner(head, places, roads, f.text("start", 40), f.text("goal", 40), f.text("tieBreak", NOTE), f.awards("awards"));
  }

  private static Stage threat(Fields f, Head head) {
    f.only("number", "type", "title", "fieldNote", "brief", "maxPoints", "message", "priors", "table", "awards");
    List<String> message = f.strings("message", 1, 30);
    Fields priors = f.object("priors");
    priors.only("threat", "normal");
    List<WordOdds> table = new ArrayList<>();
    for (Fields w : f.objects("table", 1, 100)) {
      w.only("word", "threat", "normal");
      table.add(new WordOdds(w.text("word", 40), w.num("threat"), w.num("normal")));
    }
    return new ThreatFilter(head, message, priors.num("threat"), priors.num("normal"), table, f.awards("awards"));
  }

  private static Stage rules(Fields f, Head head) {
    f.only("number", "type", "title", "fieldNote", "brief", "maxPoints", "facts", "known", "rules", "conclusions", "awards");
    List<Fact> facts = new ArrayList<>();
    for (Fields fact : f.objects("facts", 1, 60)) {
      fact.only("id", "text");
      facts.add(new Fact(fact.text("id", 40), fact.text("text", LABEL)));
    }
    List<Rule> rules = new ArrayList<>();
    for (Fields r : f.objects("rules", 1, 60)) {
      r.only("id", "ifAll", "then");
      rules.add(new Rule(r.text("id", 40), r.strings("ifAll", 1, 10), r.text("then", 40)));
    }
    return new RuleEngine(head, facts, f.strings("known", 1, 60), rules, f.strings("conclusions", 1, 12), f.awards("awards"));
  }

  private static Stage rag(Fields f, Head head) {
    f.only("number", "type", "title", "fieldNote", "brief", "maxPoints", "question", "chunkSizes", "topKs", "maxRuns", "configs");
    String question = f.text("question", LABEL);
    List<String> chunkSizes = f.strings("chunkSizes", 1, 6);
    List<Integer> topKs = new ArrayList<>();
    JsonNode ks = f.node.path("topKs");
    if (ks.isArray()) {
      for (JsonNode k : ks) {
        if (k.isIntegralNumber() && k.canConvertToInt()) topKs.add(k.intValue());
        else f.problems.add(f.path + ".topKs", "must hold whole numbers.");
      }
    }
    int maxRuns = f.integer("maxRuns");
    List<RagConfig> configs = new ArrayList<>();
    for (Fields c : f.objects("configs", 1, 40)) {
      c.only("id", "chunkSize", "topK", "chunks", "reply", "award");
      configs.add(new RagConfig(c.text("id", 40), c.text("chunkSize", 40), c.integer("topK"), c.strings("chunks", 0, 10), c.text("reply", NOTE), c.decimal("award")));
    }
    return new RagFix(head, question, chunkSizes, topKs, maxRuns, configs);
  }

  // ---- reading helpers ----------------------------------------------------------------------------

  /**
   * The problems found so far, each with the place of the field. A place inside a stage reads "Stage 3 · field" (the
   * stage's own number), one inside a mission "Mission 2 · field", anything else keeps its JSON path.
   */
  private static final class Problems {

    private static final Pattern MISSION = Pattern.compile("^missions\\[(\\d+)](?:\\.(.*))?$");

    private final List<String> list = new ArrayList<>();
    private final Map<String, String> stageLabels = new LinkedHashMap<>();

    /** Names the stage read at {@code path} ("missions[0].stages[1]") for every later problem inside it. */
    void label(String path, String label) {
      stageLabels.put(path, label);
    }

    void add(String path, String message) {
      list.add(path.isEmpty() ? message : place(path) + ": " + message);
    }

    private String place(String path) {
      for (Map.Entry<String, String> stage : stageLabels.entrySet()) {
        if (path.equals(stage.getKey())) return stage.getValue();
        if (path.startsWith(stage.getKey() + ".")) return stage.getValue() + " · " + path.substring(stage.getKey().length() + 1);
        if (path.startsWith(stage.getKey() + "[")) return stage.getValue() + " · " + path.substring(stage.getKey().length());
      }
      Matcher mission = MISSION.matcher(path);
      if (mission.matches()) {
        String name = "Mission " + (Integer.parseInt(mission.group(1)) + 1);
        return mission.group(2) == null ? name : name + " · " + mission.group(2);
      }
      return path;
    }

    boolean isEmpty() {
      return list.isEmpty();
    }

    List<String> list() {
      return list;
    }
  }

  /** One JSON object being read; every accessor records a problem and returns a harmless default when the field is wrong. */
  private static final class Fields {

    final JsonNode node;
    final String path;
    final Problems problems;

    Fields(JsonNode node, String path, Problems problems) {
      this.node = node;
      this.path = path;
      this.problems = problems;
    }

    private String at(String name) {
      return path.isEmpty() ? name : path + "." + name;
    }

    void only(String... names) {
      if (!node.isObject()) return;
      List<String> allowed = List.of(names);
      for (String name : node.propertyNames()) {
        if (!allowed.contains(name)) problems.add(at(name), "is not a field of this part of the file.");
      }
    }

    String text(String name, int max) {
      JsonNode value = node.path(name);
      if (!value.isString() || value.stringValue().isBlank()) {
        problems.add(at(name), "must be a non-empty text.");
        return "";
      }
      String text = value.stringValue().strip();
      if (text.length() > max) {
        problems.add(at(name), "is longer than " + max + " characters.");
        return text.substring(0, max);
      }
      return text;
    }

    double num(String name) {
      JsonNode value = node.path(name);
      if (!value.isNumber()) {
        problems.add(at(name), "must be a number.");
        return 0;
      }
      return value.doubleValue();
    }

    BigDecimal decimal(String name) {
      JsonNode value = node.path(name);
      if (!value.isNumber()) {
        problems.add(at(name), "must be a number.");
        return BigDecimal.ZERO;
      }
      return value.decimalValue();
    }

    int integer(String name) {
      JsonNode value = node.path(name);
      if (!value.isIntegralNumber() || !value.canConvertToInt()) {
        problems.add(at(name), "must be a whole number.");
        return 0;
      }
      return value.intValue();
    }

    boolean bool(String name) {
      JsonNode value = node.path(name);
      if (!value.isBoolean()) {
        problems.add(at(name), "must be true or false.");
        return false;
      }
      return value.booleanValue();
    }

    Fields object(String name) {
      JsonNode value = node.path(name);
      if (!value.isObject()) problems.add(at(name), "must be an object.");
      return new Fields(value, at(name), problems);
    }

    List<Fields> objects(String name, int min, int max) {
      JsonNode value = node.path(name);
      List<Fields> items = new ArrayList<>();
      if (!value.isArray()) {
        problems.add(at(name), "must be a list.");
        return items;
      }
      if (value.size() < min || value.size() > max) problems.add(at(name), "needs " + (min == max ? "exactly " + min : min + " to " + max) + " entries.");
      for (int i = 0; i < Math.min(value.size(), max); i++) {
        JsonNode item = value.get(i);
        if (!item.isObject()) problems.add(at(name) + "[" + i + "]", "must be an object.");
        else items.add(new Fields(item, at(name) + "[" + i + "]", problems));
      }
      return items;
    }

    List<String> strings(String name, int min, int max) {
      JsonNode value = node.path(name);
      List<String> items = new ArrayList<>();
      if (!value.isArray()) {
        problems.add(at(name), "must be a list of texts.");
        return items;
      }
      if (value.size() < min || value.size() > max) problems.add(at(name), "needs " + min + " to " + max + " entries.");
      for (JsonNode item : value) {
        if (!item.isString() || item.stringValue().isBlank()) problems.add(at(name), "must hold non-empty texts only.");
        else items.add(item.stringValue().strip());
      }
      return items;
    }

    /** A list of numbers. */
    List<Double> numbers(String name, int min, int max) {
      JsonNode value = node.path(name);
      List<Double> items = new ArrayList<>();
      if (!value.isArray()) {
        problems.add(at(name), "must be a list of numbers.");
        return items;
      }
      if (value.size() < min || value.size() > max) problems.add(at(name), "needs " + min + " to " + max + " entries.");
      for (JsonNode item : value) {
        if (!item.isNumber()) problems.add(at(name), "must hold numbers only.");
        else items.add(item.doubleValue());
      }
      return items;
    }

    Map<String, BigDecimal> awards(String name) {
      JsonNode value = node.path(name);
      Map<String, BigDecimal> awards = new LinkedHashMap<>();
      if (!value.isObject()) {
        problems.add(at(name), "must be an object.");
        return awards;
      }
      for (Map.Entry<String, JsonNode> entry : value.properties()) {
        if (!entry.getValue().isNumber()) problems.add(at(name) + "." + entry.getKey(), "must be a number.");
        else awards.put(entry.getKey(), entry.getValue().decimalValue());
      }
      return awards;
    }
  }
}
