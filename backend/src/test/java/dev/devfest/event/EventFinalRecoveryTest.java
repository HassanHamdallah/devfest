package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * The final's recovery levels through the real API (rules section 9): the path by K, the strict stage order, the neutral
 * messages, the rate limits, the one-time grant of the missing keys, replays, and that no answer ever reaches a member.
 */
@Import(ManualClock.Config.class)
class EventFinalRecoveryTest extends EventIntegrationTest {

  private static final String EARLIER_FIRST = "Finish the earlier recovery stages first.";
  /** Text that only the stored answers contain: the right pipeline order, accepted Arduino lines, the right option id. */
  private static final List<String> ANSWER_MARKS = List.of(
    "\"split\",\"fit_scaler\"",
    "authorized==false&&doorOpen",
    "if(doorOpen&&!authorized){",
    "\"correct\"",
    "\"kept\"",
    "\"answer\"",
    "\"accepted\":["
  );

  @Autowired
  private ManualClock clock;

  /** 4 keys. */
  private TestTeam alpha;
  /** 3 keys: ML. */
  private TestTeam bravo;
  /** 2 keys: ML then ARDUINO. */
  private TestTeam charlie;
  /** 0 keys: ML then ARDUINO. */
  private TestTeam delta;

  @BeforeEach
  void prepare() {
    clock.reset();
    loadContent();
    alpha = team("Alpha", GroupCode.A);
    bravo = team("Bravo", GroupCode.B);
    charlie = team("Charlie", GroupCode.C);
    delta = team("Delta", GroupCode.D);
    keys(alpha, RoomCode.values());
    keys(bravo, RoomCode.AI, RoomCode.PROBLEM_SOLVING, RoomCode.LINUX);
    keys(charlie, RoomCode.AI, RoomCode.HARDWARE);
    openFinal();
  }

  // ---- the path -----------------------------------------------------------------------------------------------------

  @Test
  void thePathFollowsTheKeysAndOnlyTheCurrentStageIsSent() {
    Response none = expectOk(recoveryView(alpha));
    assertEquals(List.of(), none.json("$.levels"));
    assertNull(none.json("$.currentLevel"));

    Response ml = expectOk(recoveryView(bravo));
    assertEquals("RECOVERY", ml.json("$.status"));
    assertEquals(List.of("ML"), ml.json("$.levels"));
    assertEquals("ML", ml.json("$.currentLevel"));
    assertEquals(1, (int) ml.json("$.currentStage"));
    assertEquals(List.of("motion_score", "access_attempts", "signal_strength", "device_type", "investigation_result"), ml.json("$.mlStage.leakage.features"));
    assertNull(ml.json("$.mlStage.pipeline"), "only the current stage's data");
    assertNull(ml.json("$.arduino"));
    assertEquals(List.of(false, false, false, false, false, false), ml.json("$.progress[0].stages[*].passed"));
    assertEquals(List.of("Data Leakage", "Pipeline Repair", "Data Cleaning", "Model Selection", "Threshold Tuning", "Evidence Extraction"), ml.json("$.progress[0].stages[*].name"));
    assertNull(ml.json("$.progress[0].token"), "the token only once the level is passed");

    for (TestTeam team : List.of(charlie, delta)) {
      Response both = expectOk(recoveryView(team));
      assertEquals(List.of("ML", "ARDUINO"), both.json("$.levels"));
      assertEquals("ML", both.json("$.currentLevel"));
      assertEquals(List.of(false), both.json("$.progress[1].stages[*].passed"));
    }
    assertEquals(4, count("SELECT count(*) FROM team_recovery"), "K is recorded for every team when the final opens");
  }

  @Test
  void stagesArePlayedInOrderAndNeverSkipped() {
    expectError(mlCheck(bravo, 2, ML_ANSWERS.get(1)), 409, EARLIER_FIRST);
    expectError(mlCheck(bravo, 6, ML_ANSWERS.get(5)), 409, EARLIER_FIRST);
    expectError(arduinoRun(bravo, UUID.randomUUID(), arduinoBody(fixedArduinoProgram(), "A")), 409, "Your team does not play this level.");
    expectError(arduinoRun(delta, UUID.randomUUID(), arduinoBody(fixedArduinoProgram(), "A")), 409, EARLIER_FIRST);
    expectError(mlCheck(bravo, 7, ML_ANSWERS.get(0)), 404, "Stage not found.");
    assertEquals(0, count("SELECT count(*) FROM recovery_check"), "a refused check is not stored");

    Response first = expectOk(mlCheck(bravo, 1, ML_ANSWERS.get(0)));
    assertEquals(true, first.json("$.passed"));
    assertEquals("Leakage removed. Accuracy drops to 76%, exposing the real problem.", first.json("$.message"));
    clock.advance(Duration.ofSeconds(2));
    expectError(mlCheck(bravo, 1, ML_ANSWERS.get(0)), 409, "This stage is already passed.");
    assertEquals(2, (int) expectOk(recoveryView(bravo)).json("$.currentStage"));
    assertEquals(List.of("load", "fit_scaler", "split", "transform", "train", "evaluate"), expectOk(recoveryView(bravo)).json("$.mlStage.pipeline.startOrder"));
    assertEquals("Fit Scaler on Training Data", expectOk(recoveryView(bravo)).json("$.mlStage.pipeline.relabel.label"));
  }

  @Test
  void theWholeMlLevelWithWrongAnswersNeutralMessagesAndTheRateLimit() {
    List<String> wrongMessages = List.of(
      "One selected feature contains post-investigation information.",
      "Pipeline still leaks validation information.",
      "Cleaning rejected.",
      "Model rejected. Check both constraints.",
      "Not all constraints are satisfied.",
      "Incorrect extraction."
    );
    for (int stage = 1; stage <= 6; stage++) {
      for (int i = 0; i < 2; i++) {
        Response wrong = expectOk(mlCheck(bravo, stage, ML_WRONG.get(stage - 1)));
        assertEquals(false, wrong.json("$.passed"));
        assertEquals(wrongMessages.get(stage - 1), wrong.json("$.message"), "stage " + stage);
        Response tooSoon = mlCheck(bravo, stage, ML_ANSWERS.get(stage - 1));
        assertEquals(429, tooSoon.status(), "one check per 2 seconds");
        assertEquals("Wait 2 seconds before the next check.", tooSoon.detail());
        clock.advance(Duration.ofSeconds(2));
      }
      Response right = expectOk(mlCheck(bravo, stage, ML_ANSWERS.get(stage - 1)));
      assertEquals(true, right.json("$.passed"), "stage " + stage);
      assertEquals(stage == 6, right.json("$.recoveryFinished"));
      clock.advance(Duration.ofSeconds(2));
      bravo = fresh(bravo);
    }
    assertEquals(18, count("SELECT count(*) FROM recovery_check WHERE team_id = " + bravo.id()), "12 wrong, 6 right; refused checks are not stored");
    assertEquals("RECOVERY", db.queryForObject("SELECT source FROM collected_clue WHERE team_id = ? AND room_id = ?", String.class, bravo.id(), rooms.get(RoomCode.HARDWARE)));
    Response done = expectOk(recoveryView(bravo));
    assertEquals(true, done.json("$.finished"));
    assertEquals("IN_FINAL", done.json("$.status"));
    assertEquals("ML-8842", done.json("$.progress[0].token"));
    assertNull(done.json("$.currentLevel"));
    Response unlock = expectOk(get("/team/final", bravo.token()));
    assertEquals("UNLOCK", unlock.json("$.screen"));
    assertEquals(List.of(Map.of("roomName", "Hardware", "key", CLUE_KEYS.get(RoomCode.HARDWARE))), unlock.json("$.unlock.recoveredKeys"));
    assertEquals(0, count("SELECT count(*) FROM score_deduction"), "wrong checks cost nothing");
    expectError(mlCheck(bravo, 6, ML_ANSWERS.get(5)), 409, "Your recovery is already complete.");
    expectOk(unlockWithKeys(bravo));
  }

  @Test
  void theThresholdEdgesAndItsMetricsComeFromTheServer() {
    recordPassedBefore(bravo, "ML", 5);
    Response stage = expectOk(recoveryView(bravo));
    assertEquals(5, (int) stage.json("$.currentStage"));
    assertEquals(56, (int) stage.json("$.mlStage.threshold.metrics.length()"));
    assertFalse(stage.body().contains("\"requirement\""), "the requirement is only in the prompt text");
    for (String t : List.of("0.41", "0.54", "0.25", "0.80")) {
      Response wrong = expectOk(mlCheck(bravo, 5, "{\"threshold\":" + t + "}"));
      assertEquals(false, wrong.json("$.passed"), t);
      clock.advance(Duration.ofSeconds(2));
    }
    Response atEdge = expectOk(mlCheck(bravo, 5, "{\"threshold\":0.42}"));
    assertEquals(true, atEdge.json("$.passed"));
    assertEquals(0.75, ((Number) atEdge.json("$.metrics.precision")).doubleValue());
    assertEquals(0.93, ((Number) atEdge.json("$.metrics.recall")).doubleValue());
    clock.advance(Duration.ofSeconds(2));
    expectError(mlCheck(bravo, 6, "{\"word\":null}"), 400, "Enter the recovered word.");

    TestTeam other = delta;
    recordPassedBefore(other, "ML", 5);
    assertEquals(true, expectOk(mlCheck(other, 5, "{\"threshold\":0.53}")).json("$.passed"));
    clock.advance(Duration.ofSeconds(2));
    expectError(mlCheck(charlie, 1, "{\"features\":[\"label\"]}"), 400, "Keep only listed features, each at most once.");
    recordPassedBefore(charlie, "ML", 5);
    expectError(mlCheck(charlie, 5, "{\"threshold\":0.9}"), 400, "The threshold must be on the slider (0.25 to 0.8).");
    assertEquals(0, count("SELECT count(*) FROM recovery_check WHERE team_id = %d AND stage = 5".formatted(charlie.id())), "a malformed check is not stored");
  }

  // ---- arduino ------------------------------------------------------------------------------------------------------

  @Test
  void theArduinoLevelRunsTheHiddenTestsAndFinishesTheRecovery() {
    recordPassedBefore(delta, "ARDUINO", 1);
    Response view = expectOk(recoveryView(delta));
    assertEquals("ARDUINO", view.json("$.currentLevel"));
    assertNull(view.json("$.mlStage"));
    assertEquals(89, (int) view.json("$.arduino.lines.length()"));
    assertEquals("bool entered = false;", view.json("$.arduino.lines[13].code"));
    assertEquals(88, (int) view.json("$.arduino.slot.afterLine"));
    assertEquals(List.of("A", "B", "C", "D"), view.json("$.arduino.slot.options[*].letter"));
    assertEquals(8, (int) view.json("$.arduino.tests.length()"));
    assertEquals(7, (int) view.json("$.arduino.expectedBehaviour.length()"));
    assertEquals("The alarm stays on until Reset is pressed.", view.json("$.arduino.expectedBehaviour[6]"));
    assertEquals(List.of("ML-8842"), view.json("$.progress[?(@.level == 'ML')].token"));
    for (String mark : ANSWER_MARKS) assertFalse(view.body().contains(mark), mark + " in " + view.body());

    String right = rightLetter(delta);
    String wrongLetter = right.equals("A") ? "B" : "A";
    Response untouched = expectOk(arduinoRun(delta, UUID.randomUUID(), arduinoBody(arduinoProgram(), wrongLetter)));
    assertEquals(false, untouched.json("$.passed"));
    assertEquals(List.of("T1"), untouched.json("$.tests[?(@.passed == true)].id"));
    assertEquals("Not all hidden tests pass yet.", untouched.json("$.message"));
    clock.advance(Duration.ofSeconds(2));
    expectError(arduinoRun(delta, UUID.randomUUID(), arduinoBody(arduinoProgram(), right)), 429, "Wait 1 second before the next check.");
    clock.advance(Duration.ofSeconds(1));

    List<String> outside = fixedArduinoProgram();
    outside.set(31, "    authorized = false;");
    Response changed = expectOk(arduinoRun(delta, UUID.randomUUID(), arduinoBody(outside, right)));
    assertEquals("A line outside the faulty logic was changed.", changed.json("$.message"));
    assertEquals(List.of(), changed.json("$.tests[?(@.passed == true)].id"));
    clock.advance(Duration.ofSeconds(3));

    Response lastTests = expectOk(recoveryView(fresh(delta)));
    assertEquals(8, (int) lastTests.json("$.lastTests.length()"));
    assertEquals(List.of(), lastTests.json("$.lastTests[?(@.passed == true)].id"), "the latest run's results");

    delta = fresh(delta);
    Response pass = expectOk(arduinoRun(delta, UUID.randomUUID(), arduinoBody(fixedArduinoProgram(), right)));
    assertEquals(true, pass.json("$.passed"));
    assertEquals(true, pass.json("$.recoveryFinished"));
    assertEquals("RCV-7314", pass.json("$.token"));
    assertEquals("All hidden tests pass.", pass.json("$.message"));
    assertEquals(4, count("SELECT count(*) FROM collected_clue WHERE team_id = %d AND source = 'RECOVERY'".formatted(delta.id())), "0 keys: all four granted");
    Response unlock = expectOk(get("/team/final", delta.token()));
    assertEquals(4, (int) unlock.json("$.unlock.recoveredKeys.length()"));
    expectOk(unlockWithKeys(delta));
  }

  @Test
  void theWrongOptionFailsOnlyTheEntryTest() {
    recordPassedBefore(charlie, "ARDUINO", 1);
    String right = rightLetter(charlie);
    for (String letter : List.of("A", "B", "C", "D")) {
      if (letter.equals(right)) continue;
      Response run = expectOk(arduinoRun(charlie, UUID.randomUUID(), arduinoBody(fixedArduinoProgram(), letter)));
      assertEquals(List.of("T7"), run.json("$.tests[?(@.passed == false)].id"), letter);
      clock.advance(Duration.ofSeconds(3));
    }
    expectError(arduinoRun(charlie, UUID.randomUUID(), arduinoBody(fixedArduinoProgram(), "E")), 400, "Choose one of the options for the missing behavior.");
    expectError(arduinoRun(charlie, UUID.randomUUID(), arduinoBody(fixedArduinoProgram().subList(0, 80), right)), 400, "Send every line of the program (89 lines).");
    expectOk(arduinoRun(charlie, UUID.randomUUID(), arduinoBody(fixedArduinoProgram(), right)));
    assertEquals(2, count("SELECT count(*) FROM collected_clue WHERE team_id = %d AND source = 'RECOVERY'".formatted(charlie.id())), "2 keys: the other two granted");
    assertEquals(4, count("SELECT count(*) FROM collected_clue WHERE team_id = " + charlie.id()));
  }

  @Test
  void theOptionsAreShuffledPerTeam() {
    List<List<String>> orders = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      TestTeam team = team("Shuffle " + i, GroupCode.values()[i % 4]);
      recordPassedBefore(team, "ARDUINO", 1);
      Response view = expectOk(recoveryView(team));
      orders.add(view.json("$.arduino.slot.options[*].code"));
      assertEquals(orders.getLast(), expectOk(recoveryView(team)).json("$.arduino.slot.options[*].code"), "stable for a team");
    }
    assertTrue(orders.stream().distinct().count() > 1, "teams do not all see the same order");
  }

  // ---- one-time grant, replays, refusals ----------------------------------------------------------------------------

  @Test
  void twoConcurrentFinishingChecksGrantTheKeysOnce() throws Exception {
    recordPassedBefore(bravo, "ML", 6);
    List<Integer> statuses = runTogether(List.of(
      () -> mlCheck(bravo, 6, ML_ANSWERS.get(5)).status(),
      () -> mlCheck(bravo, 6, ML_ANSWERS.get(5)).status()
    ));
    assertEquals(List.of(200, 409), statuses.stream().sorted().toList(), statuses.toString());
    assertEquals(1, count("SELECT count(*) FROM collected_clue WHERE team_id = %d AND source = 'RECOVERY'".formatted(bravo.id())));
    assertEquals(1, count("SELECT count(*) FROM recovery_check WHERE team_id = %d AND stage = 6 AND passed".formatted(bravo.id())));
    assertNotNull(db.queryForObject("SELECT finished_at FROM team_recovery WHERE team_id = ?", java.time.OffsetDateTime.class, bravo.id()));
    assertEquals(1, count("SELECT count(*) FROM audit_event WHERE action = 'EVENT_RECOVERY_FINISHED'"));
  }

  @Test
  void aRetryWithTheSameKeyReturnsTheSameCheck() {
    UUID key = UUID.randomUUID();
    Response first = expectOk(mlCheck(bravo, 1, key, ML_WRONG.get(0)));
    Response again = expectOk(mlCheck(bravo, 1, key, ML_WRONG.get(0)));
    assertEquals((String) first.json("$.checkId"), again.json("$.checkId"));
    assertEquals((String) first.json("$.message"), again.json("$.message"));
    expectError(mlCheck(bravo, 1, key, ML_ANSWERS.get(0)), 409, "This Idempotency-Key was already used for a different request.");
    assertEquals(1, count("SELECT count(*) FROM recovery_check"));

    recordPassedBefore(delta, "ARDUINO", 1);
    UUID run = UUID.randomUUID();
    String body = arduinoBody(fixedArduinoProgram(), rightLetter(delta));
    Response finished = expectOk(arduinoRun(delta, run, body));
    Response replay = expectOk(arduinoRun(delta, run, body));
    assertEquals((String) finished.json("$.checkId"), replay.json("$.checkId"));
    assertEquals(finished.<List<Object>>json("$.tests"), replay.json("$.tests"));
    assertEquals(true, replay.json("$.recoveryFinished"));
    assertEquals("RCV-7314", replay.json("$.token"));
  }

  @Test
  void aPausedFinalOrADisqualifiedTeamCannotCheck() {
    expectOk(post("/admin/final/timer/pause", admin, null));
    expectError(mlCheck(bravo, 1, ML_ANSWERS.get(0)), 409, "The final is paused.");
    expectOk(post("/admin/final/timer/resume", admin, null));
    expectOk(post("/admin/teams/" + charlie.id() + "/disqualify", admin, "{\"reason\":\"Broke the rules of the event\"}"));
    expectError(mlCheck(charlie, 1, ML_ANSWERS.get(0)), 409, "Your team has been disqualified. Contact the organizer.");
    assertEquals(0, count("SELECT count(*) FROM recovery_check"));
  }

  @Test
  void theRecoveryNeverRestoresRoomPointsOrChangesTheScoring() {
    String rooms = "$[?(@.teamName == 'Bravo')].roomsTotal";
    Object before = expectOk(get("/admin/scores", admin)).<List<Object>>json(rooms).getFirst();
    Object finalBefore = expectOk(get("/admin/scores", admin)).<List<Object>>json("$[?(@.teamName == 'Bravo')].finalScore.total").getFirst();
    finishRecovery(bravo);
    assertEquals(before, expectOk(get("/admin/scores", admin)).<List<Object>>json(rooms).getFirst());
    assertEquals(finalBefore, expectOk(get("/admin/scores", admin)).<List<Object>>json("$[?(@.teamName == 'Bravo')].finalScore.total").getFirst());
    assertEquals(List.of("IN_FINAL"), expectOk(get("/admin/scores", admin)).json("$[?(@.teamName == 'Bravo')].finalScore.status"));
  }

  @Test
  void theAdminSeesEveryTeamsRecoveryAndNoMemberDoes() {
    expectOk(mlCheck(bravo, 1, ML_WRONG.get(0)));
    clock.advance(Duration.ofSeconds(2));
    expectOk(mlCheck(bravo, 1, ML_ANSWERS.get(0)));
    clock.advance(Duration.ofSeconds(2));
    expectOk(mlCheck(bravo, 2, ML_WRONG.get(1)));
    finishRecovery(charlie);
    admin = login(db.queryForObject("SELECT email FROM app_user WHERE role = 'ADMIN' LIMIT 1", String.class));
    Response table = expectOk(get("/admin/final/recovery", admin));
    String bravoRow = "$[?(@.teamName == 'Bravo')]";
    assertEquals(List.of(3), table.json(bravoRow + ".keysAtOpen"));
    assertEquals(List.of("ML"), table.json(bravoRow + ".currentLevel"));
    assertEquals(List.of(2), table.json(bravoRow + ".currentStage"));
    assertEquals(List.of(3), table.json(bravoRow + ".checks"));
    assertEquals(List.of(1), table.json(bravoRow + ".stageChecks"));
    assertEquals(List.of(false), table.json(bravoRow + ".finished"));
    String charlieRow = "$[?(@.teamName == 'Charlie')]";
    assertEquals(List.of(true), table.json(charlieRow + ".finished"));
    assertNotNull(((List<?>) table.json(charlieRow + ".finishedAt")).getFirst());
    assertNull(((List<?>) table.json(charlieRow + ".currentLevel")).getFirst());
    assertEquals(List.of(List.of()), table.json("$[?(@.teamName == 'Alpha')].levels"));
    assertEquals(403, get("/admin/final/recovery", bravo.token()).status());
  }

  @Test
  void noResponseToAMemberCarriesAnAnswer() {
    List<String> bodies = new ArrayList<>();
    for (int stage = 1; stage <= 6; stage++) {
      bodies.add(expectOk(recoveryView(delta)).body());
      bodies.add(expectOk(mlCheck(delta, stage, ML_WRONG.get(stage - 1))).body());
      clock.advance(Duration.ofSeconds(2));
      bodies.add(expectOk(mlCheck(delta, stage, ML_ANSWERS.get(stage - 1))).body());
      clock.advance(Duration.ofSeconds(2));
      delta = fresh(delta);
    }
    bodies.add(expectOk(recoveryView(delta)).body());
    bodies.add(expectOk(arduinoRun(delta, UUID.randomUUID(), arduinoBody(arduinoProgram(), "A"))).body());
    clock.advance(Duration.ofSeconds(3));
    bodies.add(expectOk(arduinoRun(delta, UUID.randomUUID(), arduinoBody(fixedArduinoProgram(), rightLetter(delta)))).body());
    bodies.add(expectOk(recoveryView(delta)).body());
    bodies.add(expectOk(get("/team/final", delta.token())).body());
    for (String body : bodies) {
      for (String mark : ANSWER_MARKS) assertFalse(body.contains(mark), mark + " leaked: " + body);
      assertFalse(body.contains("\"minRecall\""), "the threshold requirement is never sent: " + body);
    }
  }

  @Test
  void anOldContentFileWithTheSingleRecoveryQuestionIsRefusedClearly() {
    // The file is checked before the read-only rule, so the open final does not hide the message.
    String old = finalContentJson().replaceFirst("\"recoveryLevels\":", "\"recovery\":{\"question\":\"Q\",\"acceptedAnswers\":[\"A\"]},\"recoveryLevels\":");
    Response refused = importFinalContent(old);
    assertEquals(400, refused.status(), refused.body());
    assertEquals(
      List.of(
        "recovery: the single Recovery Challenge question is retired. Replace the \"recovery\" block with \"recoveryLevels\" (\"ml\" and \"arduino\"), as in docs/final/last-shipment-content.json."
      ),
      refused.json("$.problems")
    );
  }

  @Test
  void theAdminFinalTableShowsKeysPathLevelFinishAndTheCryptoWindow() {
    List<TestTeam> teams = List.of(alpha, bravo, charlie, delta);
    Response before = expectOk(get("/admin/final/board", admin));
    String[] names = { "Alpha", "Bravo", "Charlie", "Delta" };
    List<Integer> keys = List.of(4, 3, 2, 0);
    List<String> paths = List.of("NONE", "ML", "ML_ARDUINO", "ML_ARDUINO");
    for (int i = 0; i < names.length; i++) {
      String row = "$[?(@.teamName == '" + names[i] + "')]";
      assertEquals(List.of(keys.get(i)), before.json(row + ".keysHeld"), names[i]);
      assertEquals(List.of(paths.get(i)), before.json(row + ".recoveryPath"), names[i]);
    }
    assertNull(((List<?>) before.json("$[?(@.teamName == 'Alpha')].recoveryLevel")).getFirst(), "four keys need no recovery");
    assertEquals(List.of(0), before.json("$[?(@.teamName == 'Alpha')].cryptoFromSeconds"));
    assertNull(((List<?>) before.json("$[?(@.teamName == 'Alpha')].cryptoToSeconds")).getFirst(), "the window runs until the end: no fourth file yet");
    for (String name : List.of("Bravo", "Charlie", "Delta")) {
      String row = "$[?(@.teamName == '" + name + "')]";
      assertEquals(List.of("ML"), before.json(row + ".recoveryLevel"), name);
      assertEquals(List.of(1), before.json(row + ".recoveryStage"), name);
      assertNull(((List<?>) before.json(row + ".recoveryFinishedAtSeconds")).getFirst(), name);
      assertNull(((List<?>) before.json(row + ".cryptoFromSeconds")).getFirst(), "no window before the recovery is finished: " + name);
    }

    expectOk(mlCheck(delta, 1, ML_ANSWERS.get(0)));
    clock.advance(Duration.ofSeconds(2));
    expectOk(mlCheck(delta, 2, ML_ANSWERS.get(1)));
    recordPassedBefore(charlie, "ARDUINO", 1);
    clock.advance(Duration.ofMinutes(5));
    recordPassedBefore(bravo, "ML", 6);
    expectOk(mlCheck(bravo, 6, ML_ANSWERS.get(5)));

    admin = login(db.queryForObject("SELECT email FROM app_user WHERE role = 'ADMIN' LIMIT 1", String.class));
    Response after = expectOk(get("/admin/final/board", admin));
    String delta3 = "$[?(@.teamName == 'Delta')]";
    assertEquals(List.of("ML"), after.json(delta3 + ".recoveryLevel"));
    assertEquals(List.of(3), after.json(delta3 + ".recoveryStage"));
    assertEquals(List.of(2), after.json(delta3 + ".recoveryChecks"));
    String charlieRow = "$[?(@.teamName == 'Charlie')]";
    assertEquals(List.of("ARDUINO"), after.json(charlieRow + ".recoveryLevel"));
    assertEquals(List.of(1), after.json(charlieRow + ".recoveryStage"));
    String bravoRow = "$[?(@.teamName == 'Bravo')]";
    int finishedAt = ((List<Integer>) after.json(bravoRow + ".recoveryFinishedAtSeconds")).getFirst();
    assertEquals(302, finishedAt, "2 s into the run plus 5 minutes, in active seconds");
    assertEquals(List.of(finishedAt), after.json(bravoRow + ".cryptoFromSeconds"));
    assertNull(((List<?>) after.json(bravoRow + ".cryptoToSeconds")).getFirst(), "until the end: no fourth file yet");
    assertNull(((List<?>) after.json(bravoRow + ".recoveryLevel")).getFirst(), "a finished recovery has no current level");
    assertEquals(List.of("Unlock"), after.json(bravoRow + ".stage"));
    assertEquals(teams.size(), (int) after.json("$.length()"));
  }

  @Test
  void theRecoverySolutionsAreForTheAdminOnlyAndCarryEveryAnswer() {
    assertEquals(401, get("/admin/final/recovery-solutions", null).status());
    assertEquals(403, get("/admin/final/recovery-solutions", bravo.token()).status());
    assertEquals(403, get("/admin/final/recovery-solutions", managers.get(RoomCode.AI)).status());
    Response solutions = expectOk(get("/admin/final/recovery-solutions", admin));
    assertEquals(List.of("NV-4K7", "NV-9T2", "NV-1M8", "NV-6R3"), solutions.json("$.caseKeys[*].keyReference"));
    assertEquals(List.of("C", "A", "D", "B"), solutions.json("$.caseKeys[*].key"), "clue 1 opens with C, clue 2 with A, clue 3 with D, clue 4 with B");
    assertEquals(6, (int) solutions.json("$.ml.length()"));
    assertEquals(List.of("Keep: motion_score, access_attempts, signal_strength, device_type", "Remove: investigation_result"), solutions.json("$.ml[0].answer"));
    assertEquals("Any threshold from 0.42 to 0.53 (12 slider steps).", solutions.json("$.ml[4].answer[0]"));
    List<String> evidence = solutions.json("$.ml[5].answer");
    assertEquals("Word: CLUE", evidence.getLast());
    assertEquals(List.of("B1", "B2", "B3", "B4", "B5", "B6"), solutions.json("$.arduino.bugs[*].id"));
    assertEquals("B", solutions.json("$.arduino.slot.correctOption"));
    assertEquals(List.of(false, true, false, false), solutions.json("$.arduino.slot.options[*].correct"));
    assertTrue(((String) solutions.json("$.arduino.slot.options[0].why")).contains("before the door was ever opened"));

    db.update("DELETE FROM final_content");
    expectError(get("/admin/final/recovery-solutions", admin), 409, "Import the final content first.");
  }

  // ---- helpers ------------------------------------------------------------------------------------------------------

  private void keys(TestTeam team, RoomCode... codes) {
    for (RoomCode code : codes) {
      db.update("INSERT INTO collected_clue(team_id, room_id, source, collected_at) VALUES (?, ?, 'MANUAL', now())", team.id(), rooms.get(code));
    }
  }

  private int count(String sql) {
    return db.queryForObject(sql, Integer.class);
  }

  private TestTeam fresh(TestTeam team) {
    String email = db.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, team.accountId());
    return new TestTeam(team.id(), team.name(), team.group(), team.accountId(), login(email));
  }

  private List<Integer> runTogether(List<Callable<Integer>> calls) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(calls.size());
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (Callable<Integer> call : calls) {
        futures.add(pool.submit(() -> {
          go.await();
          return call.call();
        }));
      }
      go.countDown();
      List<Integer> results = new ArrayList<>();
      for (Future<Integer> future : futures) results.add(future.get());
      return results;
    } finally {
      pool.shutdownNow();
    }
  }
}
