package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The shipped docs/final/last-shipment-content.json against its sources, and every validation rule. */
class ShipmentContentTest {

  private static final Path SHIPMENT = Path.of("..", "docs", "final", "last-shipment");
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private static String shipped() throws Exception {
    return Files.readString(Path.of("..", "docs", "final", "last-shipment-content.json"), StandardCharsets.UTF_8);
  }

  private static ShipmentContent parse(String json) {
    return JSON.readValue(json, ShipmentContent.class);
  }

  /** The shipped file with one change applied to its tree. */
  private static List<String> problemsAfter(Consumer<ObjectNode> change) throws Exception {
    ObjectNode tree = (ObjectNode) JSON.readTree(shipped());
    change.accept(tree);
    return ShipmentContentRules.problems(JSON.treeToValue(tree, ShipmentContent.class));
  }

  // ---- the shipped file -------------------------------------------------------------------------

  @Test
  void theShippedFileIsValid() throws Exception {
    ShipmentContent content = parse(shipped());
    assertEquals(List.of(), ShipmentContentRules.problems(content));
    assertEquals(2, content.format());
    assertEquals(25, content.fullUntilMinutes());
    assertEquals("THE LAST SHIPMENT", content.mission().title());
    assertEquals(12, content.suspects().size());
    assertEquals("S11", content.correctSuspect());
    assertEquals("Samer Murad", content.suspect("S11").orElseThrow().name());
    assertEquals("Vendor Relations Officer", content.suspect("S11").orElseThrow().position());
    assertEquals("Procurement Analyst", content.suspect("S05").orElseThrow().position());
  }

  @Test
  void eachClueMatchesItsSourceFilesAndKey() throws Exception {
    ShipmentContent content = parse(shipped());
    List<String> titles = List.of("ORION_SHIPMENT_NOTICE", "SECURITY_ACCESS_REPORT", "WAREHOUSE_MOVEMENT_REPORT", "STAFF_ACTIVITY_AUDIT");
    List<String> keys = List.of("A", "B", "C", "D");
    for (int n = 1; n <= 4; n++) {
      ShipmentContent.Clue clue = content.clue(n);
      assertEquals(titles.get(n - 1), clue.title());
      assertEquals(titles.get(n - 1) + ".enc", clue.encFile());
      assertEquals("clue" + n + "_key.rsa", clue.rsaFile());
      String reference = clue.keyReference();
      assertEquals(keys.get(n - 1), content.keyIndex().stream().filter(k -> k.reference().equals(reference)).findFirst().orElseThrow().key());
      String report = Files.readString(SHIPMENT.resolve("clues").resolve(titles.get(n - 1) + ".txt"), StandardCharsets.UTF_8);
      assertEquals(report.replace("\r\n", "\n").stripTrailing(), clue.report());
      // The secret file ends with a Windows line ending; as copied from the file it still opens the clue.
      String secretFile = Files.readString(SHIPMENT.resolve("keys").resolve("clue" + n + "_secret.txt"), StandardCharsets.US_ASCII);
      String secret = secretFile.strip();
      assertEquals(64, secret.length());
      assertEquals(Passphrase.sha256Hex(secret), clue.passphraseSha256(), "the hash is of the secret without any line ending");
      assertTrue(content.opens(n, secretFile), "with the file's line ending");
      assertTrue(content.opens(n, secret.toUpperCase()), "upper case");
      for (int other = 1; other <= 4; other++) if (other != n) assertFalse(content.opens(other, secret), "clue " + other + " with secret " + n);
    }
  }

  @Test
  void theShippedFileNeverHoldsAPassphrase() throws Exception {
    String file = shipped();
    for (int n = 1; n <= 4; n++) {
      String secret = Files.readString(SHIPMENT.resolve("keys").resolve("clue" + n + "_secret.txt"), StandardCharsets.US_ASCII).strip();
      assertFalse(file.contains(secret), "secret " + n);
    }
  }

  // ---- validation -------------------------------------------------------------------------------

  @Test
  void everyProblemIsListedAtOnce() throws Exception {
    List<String> problems = problemsAfter(tree -> {
      tree.put("format", 1);
      tree.put("fullUntilMinutes", 41);
      ((ObjectNode) tree.get("mission")).put("intro", " ");
      tree.put("correctSuspect", "S99");
    });
    assertEquals(
      List.of(
        "format: must be 2.",
        "fullUntilMinutes: must be a whole number from 1 to 40.",
        "mission.intro is required (at most 2000 characters).",
        "correctSuspect: must be the id of one of the suspects."
      ),
      problems
    );
  }

  @Test
  void exactlyFourCluesNumberedOneToFourInOrder() throws Exception {
    assertTrue(problemsAfter(tree -> ((ArrayNode) tree.get("clues")).remove(3)).contains(
      "clues: exactly 4 clues are required, numbered 1 to 4 in that order."
    ));
    List<String> swapped = problemsAfter(tree -> {
      ((ObjectNode) tree.get("clues").get(0)).put("number", 2);
      ((ObjectNode) tree.get("clues").get(1)).put("number", 1);
    });
    assertTrue(swapped.contains("Clue 1: number must be 1."), swapped.toString());
    assertTrue(swapped.contains("Clue 2: number must be 2."), swapped.toString());
  }

  @Test
  void eachClueNeedsItsFieldsAndALowerCaseSha256() throws Exception {
    List<String> problems = problemsAfter(tree -> {
      ObjectNode clue = (ObjectNode) tree.get("clues").get(2);
      clue.put("passphraseSha256", clue.get("passphraseSha256").asString().toUpperCase());
      clue.put("encFile", "../etc/passwd");
      clue.put("report", "");
      clue.remove("title");
      ((ObjectNode) tree.get("clues").get(3)).put("passphraseSha256", "abc");
    });
    assertTrue(problems.contains("Clue 3: passphraseSha256 must be 64 lower-case hex characters."), problems.toString());
    assertTrue(problems.contains("Clue 3: encFile and rsaFile must be plain file names (letters, digits, '.', '_', '-')."), problems.toString());
    assertTrue(problems.contains("Clue 3: report is required (at most 12000 characters)."), problems.toString());
    assertTrue(problems.contains("Clue 3: title is required (at most 120 characters)."), problems.toString());
    assertTrue(problems.contains("Clue 4: passphraseSha256 must be 64 lower-case hex characters."), problems.toString());
  }

  @Test
  void theKeyIndexCoversEveryReferenceAndUsesEachKeyOnce() throws Exception {
    List<String> twiceA = problemsAfter(tree -> ((ObjectNode) tree.get("keyIndex").get(0)).put("key", "A"));
    assertTrue(twiceA.stream().anyMatch(p -> p.startsWith("keyIndex: key A must be used exactly once")), twiceA.toString());
    List<String> unknown = problemsAfter(tree -> ((ObjectNode) tree.get("keyIndex").get(0)).put("reference", "NV-REF-9"));
    assertTrue(unknown.contains("keyIndex: NV-REF-9 is not the keyReference of any clue."), unknown.toString());
    assertTrue(unknown.stream().anyMatch(p -> p.startsWith("keyIndex: the clue reference ") && p.endsWith(" has no entry.")), unknown.toString());
    List<String> badKey = problemsAfter(tree -> ((ObjectNode) tree.get("keyIndex").get(1)).put("key", "E"));
    assertTrue(badKey.stream().anyMatch(p -> p.endsWith(": key must be one of A, B, C, D.")), badKey.toString());
    List<String> three = problemsAfter(tree -> ((ArrayNode) tree.get("keyIndex")).remove(0));
    assertTrue(three.contains("keyIndex: exactly 4 entries are required, one per clue."), three.toString());
    List<String> shared = problemsAfter(tree ->
      ((ObjectNode) tree.get("clues").get(1)).put("keyReference", tree.get("clues").get(0).get("keyReference").asString())
    );
    assertTrue(shared.stream().anyMatch(p -> p.startsWith("Clue 2: keyReference ") && p.endsWith(" is used by another clue.")), shared.toString());
  }

  @Test
  void suspectsAreTwoToThirtyWithUniqueIdsAndTheCorrectOneAmongThem() throws Exception {
    List<String> duplicate = problemsAfter(tree -> ((ObjectNode) tree.get("suspects").get(1)).put("id", "S01"));
    assertTrue(duplicate.contains("Suspect 2: the id S01 is used twice."), duplicate.toString());
    List<String> one = problemsAfter(tree -> {
      ArrayNode suspects = (ArrayNode) tree.get("suspects");
      while (suspects.size() > 1) suspects.remove(suspects.size() - 1);
      tree.put("correctSuspect", "S01");
    });
    assertEquals(List.of("suspects: from 2 to 30 suspects are required."), one);
    List<String> many = problemsAfter(tree -> {
      ArrayNode suspects = (ArrayNode) tree.get("suspects");
      for (int i = 0; i < 19; i++) {
        ObjectNode extra = suspects.addObject();
        extra.put("id", "X" + i);
        extra.put("name", "Extra " + i);
        extra.put("position", "TBD");
        extra.put("statement", "TBD");
      }
    });
    assertEquals(List.of("suspects: from 2 to 30 suspects are required."), many, "31 suspects");
    List<String> noName = problemsAfter(tree -> ((ObjectNode) tree.get("suspects").get(0)).put("name", ""));
    assertTrue(noName.contains("Suspect 1: name is required (at most 80 characters)."), noName.toString());
  }

  @Test
  void theRecoveryChallengeIsRequired() throws Exception {
    assertTrue(problemsAfter(tree -> tree.remove("recovery")).contains("recovery.question: required (at most 2000 characters)."));
    List<String> noAnswers = problemsAfter(tree -> ((ObjectNode) tree.get("recovery")).putArray("acceptedAnswers"));
    assertTrue(noAnswers.contains("recovery.acceptedAnswers: from 1 to 20 answers are required."), noAnswers.toString());
  }

  @Test
  void anEmptyFileIsOneProblem() {
    assertEquals(List.of("The file is empty."), ShipmentContentRules.problems(null));
  }

  @Test
  void theCorrectSuspectAndTheRecoveryAnswerAreCheckedOnTheServer() throws Exception {
    ShipmentContent content = parse(shipped());
    assertTrue(content.isCorrectSuspect("S11"));
    assertFalse(content.isCorrectSuspect("S05"));
    JsonNode recovery = JSON.readTree(shipped()).get("recovery");
    assertTrue(content.acceptsRecoveryAnswer(" " + recovery.get("acceptedAnswers").get(0).asString().toLowerCase() + " "));
    assertFalse(content.recoveryIsPlaceholder());
  }
}
