package dev.devfest.event.dto;

import dev.devfest.event.domain.sentinel.SentinelContent.*;
import dev.devfest.event.domain.sentinel.StageAnswer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Reads the JSON body of "submit a stage" into a {@link StageAnswer} of the stage's kind. A body with a missing,
 * misspelt or mistyped field is refused with a message for the team; nothing here knows the right answer.
 */
public final class StageAnswers {

  private static final int MAX_TEXT = 80;
  private static final int MAX_LIST = 60;

  private StageAnswers() {}

  public static StageAnswer read(Stage stage, JsonNode body) {
    if (body == null || !body.isObject()) throw bad("Send the stage's answer as a JSON object.");
    return switch (stage) {
      case OutlierHunt _ -> pick(body);
      case TuneDeploy _ -> pick(body);
      case RagFix _ -> pick(body);
      case FeatureCheck _ -> features(body);
      case NearestVehicle _ -> neighbours(body);
      case CameraAccuracy _ -> matrix(body);
      case GroupFrames _ -> cluster(body);
      case RoutePlanner _ -> route(body);
      case ThreatFilter _ -> bayes(body);
      case RuleEngine _ -> new StageAnswer.Conclusion(text(body, "conclusion"));
    };
  }

  private static StageAnswer pick(JsonNode body) {
    only(body, "config");
    return new StageAnswer.Pick(text(body, "config"));
  }

  private static StageAnswer features(JsonNode body) {
    only(body, "choices");
    JsonNode choices = body.path("choices");
    if (!choices.isObject() || choices.size() > MAX_LIST) throw bad("Send choices as an object: feature id to USE or DROP.");
    Map<String, Boolean> use = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> choice : choices.properties()) {
      String value = choice.getValue().isString() ? choice.getValue().stringValue() : "";
      if (!value.equals("USE") && !value.equals("DROP")) throw bad("Each feature is either USE or DROP.");
      use.put(choice.getKey(), value.equals("USE"));
    }
    return new StageAnswer.Features(use);
  }

  private static StageAnswer neighbours(JsonNode body) {
    only(body, "k1", "k5", "reliable");
    return new StageAnswer.Neighbours(text(body, "k1"), text(body, "k5"), whole(body, "reliable"));
  }

  private static StageAnswer matrix(JsonNode body) {
    only(body, "tp", "fp", "fn", "tn", "precision", "recall", "metric");
    return new StageAnswer.Matrix(whole(body, "tp"), whole(body, "fp"), whole(body, "fn"), whole(body, "tn"), number(body, "precision"), number(body, "recall"), text(body, "metric"));
  }

  private static StageAnswer cluster(JsonNode body) {
    only(body, "size", "x", "y");
    return new StageAnswer.Cluster(whole(body, "size"), number(body, "x"), number(body, "y"));
  }

  private static StageAnswer route(JsonNode body) {
    only(body, "order", "path", "cost");
    return new StageAnswer.Route(texts(body, "order"), texts(body, "path"), number(body, "cost"));
  }

  private static StageAnswer bayes(JsonNode body) {
    only(body, "threat", "normal", "label");
    return new StageAnswer.Bayes(number(body, "threat"), number(body, "normal"), text(body, "label"));
  }

  // ---- fields -------------------------------------------------------------------------------------

  private static void only(JsonNode body, String... names) {
    List<String> allowed = List.of(names);
    for (String name : body.propertyNames()) if (!allowed.contains(name)) throw bad("Unexpected field " + name + ".");
  }

  private static String text(JsonNode body, String name) {
    JsonNode value = body.path(name);
    if (!value.isString() || value.stringValue().isBlank() || value.stringValue().length() > MAX_TEXT) throw bad("Fill in " + name + ".");
    return value.stringValue().strip();
  }

  private static List<String> texts(JsonNode body, String name) {
    JsonNode value = body.path(name);
    if (!value.isArray() || value.size() > MAX_LIST) throw bad("Send " + name + " as a list.");
    List<String> items = new ArrayList<>();
    for (JsonNode item : value) {
      if (!item.isString() || item.stringValue().isBlank() || item.stringValue().length() > MAX_TEXT) throw bad("Send " + name + " as a list of places.");
      items.add(item.stringValue().strip());
    }
    return items;
  }

  private static int whole(JsonNode body, String name) {
    JsonNode value = body.path(name);
    if (!value.isIntegralNumber() || !value.canConvertToInt()) throw bad("Enter " + name + " as a whole number.");
    return value.intValue();
  }

  private static double number(JsonNode body, String name) {
    JsonNode value = body.path(name);
    if (!value.isNumber() || Double.isNaN(value.doubleValue()) || Double.isInfinite(value.doubleValue())) throw bad("Enter " + name + " as a number.");
    return value.doubleValue();
  }

  private static IllegalArgumentException bad(String message) {
    return new IllegalArgumentException(message);
  }
}
