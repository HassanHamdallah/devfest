package dev.devfest.event.domain.sentinel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.dto.MissionDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The shipped mission file against the one it replaced (docs/ai-room/sentinel-content.v1-backup.json): the stages that
 * were not meant to change keep every number, reading, configuration, vehicle, chunk and award, and only their texts
 * move to the new story. It also holds the story rules of the AI room: nothing of the final's evidence, no old names.
 */
class SentinelShippedFileTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private static JsonNode shipped() {
    return JSON.readTree(SentinelFixture.shipped());
  }

  private static JsonNode v1() {
    return JSON.readTree(SentinelFixture.v1());
  }

  private static JsonNode stage(JsonNode root, int number) {
    for (JsonNode mission : root.get("missions")) {
      for (JsonNode stage : mission.get("stages")) {
        if (stage.get("number").intValue() == number) return stage;
      }
    }
    throw new IllegalArgumentException("stage " + number);
  }

  /** The stage without the texts a team reads; everything else must be identical. */
  private static ObjectNode withoutTexts(JsonNode stage) {
    ObjectNode copy = (ObjectNode) stage.deepCopy();
    for (String text : List.of("title", "fieldNote", "brief", "question")) copy.remove(text);
    if (copy.has("configs")) {
      for (JsonNode config : copy.get("configs")) {
        ((ObjectNode) config).remove("reply");
        if (config.has("chunks")) ((ObjectNode) config).put("chunks", config.get("chunks").size());
      }
    }
    return copy;
  }

  @Test
  void theShippedFileValidatesAndImports() {
    MissionDocument document = MissionDocument.read(shipped());
    assertEquals(60, document.passScore(), "the pass score is unchanged");
    assertEquals(List.of(), SentinelContentRules.problems(document.content()));
    assertEquals(0, document.content().totalPoints().compareTo(java.math.BigDecimal.valueOf(100)));
    assertTrue(document.content().stages().stream().allMatch(s -> s.head().maxPoints().intValue() == 10), "every stage is worth 10");
  }

  @Test
  void stagesOneFourFiveSevenAndTenKeepEveryNumberAndOnlyTheirTextsChange() {
    for (int number : List.of(1, 4, 5, 7, 10)) {
      assertEquals(withoutTexts(stage(v1(), number)), withoutTexts(stage(shipped(), number)), "stage " + number);
    }
  }

  @Test
  void stageTwoHasTheSameSixFeaturesAndRolesInTheNewOrder() {
    JsonNode before = stage(v1(), 2);
    JsonNode after = stage(shipped(), 2);
    List<String> order = new ArrayList<>();
    List<String> roles = new ArrayList<>();
    for (JsonNode feature : after.get("features")) {
      order.add(feature.get("id").stringValue());
      roles.add(feature.get("role").stringValue());
    }
    assertEquals(List.of("entry-gate", "plate-number", "entry-time", "exit-camera-time", "vehicle-type", "previous-visits"), order);
    assertEquals(List.of("USE", "DROP", "USE", "DROP", "USE", "USE"), roles);
    for (JsonNode feature : after.get("features")) {
      JsonNode old = null;
      for (JsonNode candidate : before.get("features")) if (candidate.get("id").equals(feature.get("id"))) old = candidate;
      assertEquals(old, feature, "feature " + feature.get("id").stringValue() + " is unchanged");
    }
    assertEquals(before.get("target"), after.get("target"));
    assertEquals(before.get("maxPoints"), after.get("maxPoints"));
  }

  @Test
  void stageThreeIsAChoiceAndStagesSixEightAndNineAreTypedAnswersAsWritten() {
    assertEquals(List.of("Neuron Check", "Patrol Evolution", "Best Split", "Patrol Game"), List.of(3, 6, 8, 9).stream().map(n -> stage(shipped(), n).get("title").stringValue()).toList());
    String neuron = stage(shipped(), 3).get("brief").stringValue();
    assertTrue(neuron.startsWith("A neuron in SENTINEL scores a card swipe.\nInputs: x1 = 3 (hours after closing), x2 = 2 (failed logins)\nWeights: w1 = 0.5, w2 = -2. Bias: b = 1.\n"));
    assertTrue(neuron.endsWith("What does the neuron output?"));
    assertEquals("CHOICE", stage(shipped(), 3).get("type").stringValue());
    assertEquals("a", stage(shipped(), 3).get("correct").stringValue());
    assertEquals(4, stage(shipped(), 3).get("options").size());
    for (int number : List.of(6, 8, 9)) {
      JsonNode stage = stage(shipped(), number);
      assertEquals("INPUT", stage.get("type").stringValue());
      assertFalse(stage.has("options") || stage.has("correct"), "no options on a typed stage");
    }
    assertTrue(stage(shipped(), 6).get("brief").stringValue().contains("\n    Parent A:  1 0 1 | 1 0 0\n    Parent B:  0 1 1 | 0 1 1\n"));
    assertTrue(stage(shipped(), 8).get("brief").stringValue().contains("\"After closing time?\":\n    Yes: 1 normal, 3 suspicious\n    No:  3 normal, 1 suspicious\n"));
    assertTrue(stage(shipped(), 9).get("brief").stringValue().contains("    MAX\n    ├─ MIN: 3, 5\n    ├─ MIN: 2, 9\n    └─ MIN: 4, 6\n"));
    assertEquals("{\"kind\":\"TEXT\",\"accepted\":[\"101001\"]}", stage(shipped(), 6).get("answer").toString());
    assertEquals("NUMBER", stage(shipped(), 8).get("answer").get("kind").stringValue());
    assertEquals(0.19, stage(shipped(), 8).get("answer").get("value").doubleValue());
    assertEquals(0.01, stage(shipped(), 8).get("answer").get("tolerance").doubleValue());
    assertEquals(4, stage(shipped(), 9).get("answer").get("value").doubleValue());
    assertEquals(0, stage(shipped(), 9).get("answer").get("tolerance").doubleValue());
  }

  @Test
  void onlyStagesSixEightAndNineChangedTheirAnswerFormatAndFiguresWereAdded() {
    JsonNode before = v2();
    JsonNode after = shipped();
    assertEquals(before.get("story"), after.get("story"));
    assertEquals(before.get("passScore"), after.get("passScore"));
    // The formulas of stages 1, 3, 7 and 8 moved to their own "$$ " lines: only their texts differ (see SentinelContentTest).
    for (int number : List.of(2, 4, 5, 10)) {
      assertEquals(before(number, before), before(number, after), "stage " + number + " is identical");
    }
    for (int number : List.of(1, 7)) {
      assertEquals(withoutTexts(stage(before, number)), withoutTexts(stage(after, number)), "stage " + number + " is identical but for its texts");
    }
    // Stage 3 keeps its options and answer; it only gains its figure.
    assertEquals(withoutTexts(withoutFigure(stage(before, 3))), withoutTexts(withoutFigure(stage(after, 3))));
    assertFalse(stage(before, 3).has("figure"));
    assertEquals("neuron", stage(after, 3).get("figure").get("type").stringValue());
    for (int number : List.of(6, 8, 9)) {
      JsonNode old = stage(before, number);
      JsonNode now = stage(after, number);
      for (String same : List.of("number", "title", "fieldNote", "maxPoints")) assertEquals(old.get(same), now.get(same), same + " of stage " + number);
      if (number != 8) assertTrue(now.get("brief").stringValue().startsWith(old.get("brief").stringValue() + "\n"), "the brief only gains its last line, stage " + number);
    }
    assertEquals(List.of("crossover", "split", "minimax"), List.of(6, 8, 9).stream().map(n -> stage(after, n).get("figure").get("type").stringValue()).toList());
  }

  private static JsonNode before(int number, JsonNode root) {
    return stage(root, number);
  }

  private static JsonNode withoutTexts(JsonNode stage) {
    ObjectNode copy = (ObjectNode) stage.deepCopy();
    copy.remove("fieldNote");
    copy.remove("brief");
    return copy;
  }

  private static JsonNode withoutFigure(JsonNode stage) {
    ObjectNode copy = (ObjectNode) stage.deepCopy();
    copy.remove("figure");
    return copy;
  }

  private static JsonNode v2() {
    return JSON.readTree(SentinelFixture.v2());
  }

  // ---- field notes help; they never solve --------------------------------------------------------------------------

  @Test
  void everyFieldNoteStartsWithNoorsNotesAndNoneContainsAnAnswerOrAWorkedResult() {
    JsonNode root = shipped();
    for (int number = 1; number <= 10; number++) {
      String note = stage(root, number).get("fieldNote").stringValue();
      assertTrue(note.startsWith("Noor's notes:"), "stage " + number + ": " + note);
    }
    // The worked results of the four multiple choice stages and of the two stages that had a hint of their answer.
    for (String result : List.of("-1.5", "101001", "101011", "0.811", "0.19", "= 4", "reliable", "recall matters", "k = 5")) {
      for (int number : List.of(3, 4, 5, 6, 8, 9)) {
        assertFalse(stage(root, number).get("fieldNote").stringValue().contains(result), "stage " + number + " note gives away '" + result + "'");
      }
    }
    for (int number : List.of(3)) {
      String note = stage(root, number).get("fieldNote").stringValue();
      for (JsonNode option : stage(root, number).get("options")) {
        if (option.get("id").stringValue().equals("a")) assertFalse(note.contains(option.get("text").stringValue()) && option.get("text").stringValue().length() > 1, "the note never states the answer: " + note);
      }
    }
  }

  // ---- the story ---------------------------------------------------------------------------------------------------

  private static final Pattern PRONOUN = Pattern.compile("\\b(he|his|him|himself)\\b", Pattern.CASE_INSENSITIVE);

  @Test
  void theStoryIsTheNewCaseAndNoOldNameOrPronounIsLeft() {
    JsonNode root = shipped();
    String all = root.toString();
    assertTrue(root.get("story").get("start").stringValue().contains("NOVA Technologies"));
    assertTrue(root.get("story").get("start").stringValue().contains("Noor"));
    assertTrue(root.get("story").get("start").stringValue().contains("ORION"));
    assertTrue(root.get("story").get("start").stringValue().contains("Cybersecurity Engineer"));
    assertTrue(root.get("story").get("start").stringValue().contains("her investigation"));
    for (String old : List.of("Rami", "NOOR", "journalist")) assertFalse(all.contains(old), old);
    assertFalse(PRONOUN.matcher(all).find(), "the investigator is she/her: " + PRONOUN.matcher(all).results().map(m -> m.group()).toList());
    assertEquals("ECHO", root.get("missions").get(2).get("title").stringValue());
    assertEquals("noor", root.get("missions").get(2).get("id").stringValue(), "the internal id stays");
    assertTrue(root.get("missions").get(2).get("intro").stringValue().contains("NOVA"));
    assertEquals("SENTINEL RESTORED. All systems are back online. Recovered record:", root.get("story").get("passed").stringValue());
    assertTrue(root.get("story").get("passed").stringValue().chars().noneMatch(Character::isDigit));
    assertTrue(root.get("story").get("failed").stringValue().chars().noneMatch(Character::isDigit));
  }

  @Test
  void stageTenAsksEchoAboutNoorAndNothingElseMoved() {
    JsonNode rag = stage(shipped(), 10);
    assertEquals("Where was Noor last confirmed?", rag.get("question").stringValue());
    assertTrue(rag.get("brief").stringValue().contains("Ask ECHO where Noor was last confirmed."));
    assertTrue(rag.get("brief").stringValue().contains("Ask ECHO"), "the button is called Ask ECHO");
    assertTrue(rag.get("fieldNote").stringValue().contains("ECHO answers from what it retrieves."));
  }

  // ---- the AI room never reveals the final's evidence -----------------------------------------------------------------

  private static final Pattern EVENING_24H = Pattern.compile("\\b21:(0\\d|[1-3]\\d)\\b");
  private static final Pattern EVENING_12H = Pattern.compile("\\b9(:[0-5]\\d)? ?(PM|pm|p\\.m\\.)");

  @Test
  void noTextHoldsTheFinalsEvidence() {
    String all = shipped().toString();
    for (String name : List.of("Samer", "Sara", "Yazan", "Mazen", "Hiba", "Old Storage", "turnstile", "Turnstile", "routing", "Routing", "fingerprint", "Fingerprint", "Murad", "Awad")) {
      assertFalse(all.contains(name), name);
    }
    assertFalse(EVENING_24H.matcher(all).find(), "no time between 21:00 and 21:39: " + EVENING_24H.matcher(all).results().map(m -> m.group()).toList());
    assertFalse(EVENING_12H.matcher(all).find(), "no 9 PM times");
    Set<String> banned = Set.of("9:00", "9:05", "9:10", "9:15", "9:17", "9:20", "9:21", "9:25", "9:30", "9:31", "9:38");
    for (String time : banned) assertFalse(all.contains(time), time);
  }
}
