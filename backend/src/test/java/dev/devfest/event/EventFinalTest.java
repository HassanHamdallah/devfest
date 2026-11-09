package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The crypto final: qualification, the one Recovery Challenge, unlock, the four layers, the one-time verdicts and
 * suspect, the single 40-minute timer, time up, scoring and what a member may ever see.
 */
@Import(ManualClock.Config.class)
class EventFinalTest extends EventIntegrationTest {

  private static final int LEILA = 2;
  private static final int KAREEM = 1;
  private static final String NOT_QUALIFIED = "You did not qualify for the final.";
  /** Field names that must never appear in a member response (the private values, verdicts, scores). */
  private static final Pattern FORBIDDEN_FIELDS = Pattern.compile(
    "\"(p|q|d|m|plaintext|privateExponent|correctSuspect|suspectCorrect|verdictCorrect|acceptedAnswers|score|scores|total|rank|finalScore|roomScores)\"\\s*:"
  );

  @Autowired
  private ManualClock clock;

  @MockitoSpyBean
  private JdbcTemplate spiedJdbc;

  private TestTeam alpha;
  private TestTeam bravo;

  @BeforeEach
  void prepare() {
    clock.reset();
    loadContent();
    alpha = team("Alpha", GroupCode.A);
    bravo = team("Bravo", GroupCode.B);
    clues(alpha, RoomCode.values());
    clues(bravo, RoomCode.AI, RoomCode.PROBLEM_SOLVING, RoomCode.LINUX);
  }

  // ---- qualification and the single timer -------------------------------------------------------

  @Test
  void theQualificationMatrixIsEnforcedOnTheServer() {
    TestTeam charlie = team("Charlie", GroupCode.C);
    TestTeam delta = team("Delta", GroupCode.D);
    clues(charlie, RoomCode.AI, RoomCode.LINUX);
    assertEquals("LOCKED", view(alpha).json("$.status"));
    assertEquals("LOCKED", view(alpha).json("$.screen"));
    expectError(unlockWithKeys(alpha), 409, "The final is not open.");

    openFinal();
    assertEquals("IN_FINAL", view(alpha).json("$.status"));
    assertEquals("UNLOCK", view(alpha).json("$.screen"));
    assertEquals("RECOVERY", view(bravo).json("$.status"));
    assertEquals("RECOVERY", view(bravo).json("$.screen"));
    for (TestTeam unqualified : List.of(charlie, delta)) {
      Response v = view(unqualified);
      assertEquals("NOT_QUALIFIED", v.json("$.status"));
      assertEquals("NOT_QUALIFIED", v.json("$.screen"));
      assertNull(v.json("$.unlock"));
      assertNull(v.json("$.recovery"));
      assertNull(v.json("$.layer"));
      expectError(unlockWithKeys(unqualified), 409, NOT_QUALIFIED);
      expectError(startCase(unqualified), 409, NOT_QUALIFIED);
      expectError(layer(unqualified, 1, "52"), 409, NOT_QUALIFIED);
      expectError(verdict(unqualified, 3, "NO"), 409, NOT_QUALIFIED);
      expectError(suspect(unqualified, LEILA), 409, NOT_QUALIFIED);
      expectError(recover(unqualified, UUID.randomUUID(), RECOVERY_ANSWER), 409, NOT_QUALIFIED);
    }
    List<String> statuses = expectOk(get("/admin/scores", admin)).json("$[*].finalScore.status");
    assertEquals(4, statuses.size());
    assertEquals(Set.of("IN_FINAL", "RECOVERY", "NOT_QUALIFIED"), Set.copyOf(statuses));
    assertEquals(2, statuses.stream().filter("NOT_QUALIFIED"::equals).count());
  }

  @Test
  void opensOnceForEveryoneWithOneFortyMinuteServerTimer() {
    TestTeam charlie = team("Charlie", GroupCode.C);
    openFinal();
    assertEquals("RUNNING", db.queryForObject("SELECT final_phase FROM event_state", String.class));
    assertEquals(2400, (int) db.queryForObject("SELECT final_duration_seconds FROM event_state", Integer.class));
    for (TestTeam team : List.of(alpha, bravo, charlie)) {
      assertEquals(2400, (int) view(team).json("$.remainingSeconds"), team.name());
      assertEquals("RUNNING", view(team).json("$.timerPhase"));
    }
    String startedAt = view(alpha).json("$.finalStartedAt");
    assertEquals(startedAt, view(bravo).json("$.finalStartedAt"));
    clock.advance(Duration.ofMinutes(5));
    for (TestTeam team : List.of(alpha, bravo, charlie)) assertEquals(2100, (int) view(team).json("$.remainingSeconds"));
    expectOk(post("/admin/final/open", admin, null));
    assertEquals(2100, (int) view(alpha).json("$.remainingSeconds"), "opening again changes nothing and never restarts the timer");
  }

  @Test
  void theFinalOpensOnlyWithCompleteContent() {
    db.update("DELETE FROM final_content");
    db.update("UPDATE event_room SET clue_key = '' WHERE code = 'LINUX'");
    Response refused = post("/admin/final/open", admin, null);
    assertEquals(409, refused.status());
    assertTrue(refused.detail().startsWith("Complete the event content before opening the final: "), refused.detail());
    assertTrue(refused.detail().contains("final content file"), refused.detail());
    assertTrue(refused.detail().contains("Linux clue key"), refused.detail());
    assertEquals(false, expectOk(get("/admin/board", admin)).json("$.state.finalOpen"));
    List<String> codes = expectOk(get("/admin/board", admin)).json("$.alerts[*].code");
    assertTrue(codes.contains("MISSING_FINAL_CONTENT") && codes.contains("MISSING_CLUE_KEY"), codes.toString());
  }

  // ---- the Recovery Challenge -------------------------------------------------------------------

  @Test
  void oneRecoveryChallengeCollectsTheMissingClueWithTheRemainingTimeAndNoPenalty() {
    openFinal();
    clock.advance(Duration.ofMinutes(5));
    Response before = view(bravo);
    assertEquals(RECOVERY_QUESTION, before.json("$.recovery.question"));
    assertEquals(Set.of("question", "attempts"), ((Map<?, ?>) before.json("$.recovery")).keySet(), "one unified challenge, not a room's");
    for (int i = 0; i < 3; i++) {
      Response wrong = expectOk(recover(bravo, UUID.randomUUID(), "wrong " + i));
      assertEquals(false, wrong.json("$.correct"));
      assertEquals("RECOVERY", wrong.json("$.stage"));
    }
    assertEquals(0, count("SELECT count(*) FROM score_deduction"), "wrong answers cost nothing");
    assertEquals(3, (int) view(bravo).json("$.cluesCollected"));
    Response correct = expectOk(recover(bravo, UUID.randomUUID(), "  eight   bits "));
    assertEquals(true, correct.json("$.correct"));
    assertEquals("IN_FINAL", correct.json("$.stage"));
    assertEquals("RECOVERY", db.queryForObject("SELECT source FROM collected_clue WHERE team_id = ? AND room_id = ?", String.class, bravo.id(), rooms.get(RoomCode.HARDWARE)));
    assertEquals(4, count("SELECT count(*) FROM recovery_attempt"));
    Response after = view(bravo);
    assertEquals("IN_FINAL", after.json("$.status"));
    assertEquals("UNLOCK", after.json("$.screen"));
    assertEquals(2100, (int) after.json("$.remainingSeconds"), "it continues with the remaining time");
    assertEquals(List.of(CLUE_KEYS.get(RoomCode.HARDWARE)), after.json("$.evidence[?(@.roomCode == 'HARDWARE')].clueValue"));
    expectError(recover(bravo, UUID.randomUUID(), RECOVERY_ANSWER), 409, "Recovery is not available for your team.");
    expectError(recover(alpha, UUID.randomUUID(), RECOVERY_ANSWER), 409, "Recovery is not available for your team.");
  }

  @Test
  void aRecoveryRetryWithTheSameKeyReturnsTheSameAttempt() {
    openFinal();
    UUID key = UUID.randomUUID();
    Response first = expectOk(recover(bravo, key, "nope"));
    Response again = expectOk(recover(bravo, key, "nope"));
    assertEquals((String) first.json("$.attemptId"), again.json("$.attemptId"));
    expectError(recover(bravo, key, "different"), 409, "This Idempotency-Key was already used for a different request.");
    assertEquals(1, count("SELECT count(*) FROM recovery_attempt"));
  }

  // ---- unlock -----------------------------------------------------------------------------------

  @Test
  void aWrongUnlockSaysOnlyThatTheOutputsDoNotOpenTheCase() {
    openFinal();
    Response wrong = expectOk(unlock(alpha, "WHITE VAN", "RED CLAY", "WEST TOWER", "+970-59-000"));
    assertEquals(false, wrong.json("$.accepted"));
    assertEquals("These room outputs do not open the case.", wrong.json("$.message"));
    assertEquals(Set.of("accepted", "message", "waitSeconds", "status", "screen"), ((Map<?, ?>) wrong.json("$")).keySet(), "no per-field detail");
    Response shuffled = expectOk(unlock(alpha, CLUE_KEYS.get(RoomCode.PROBLEM_SOLVING), CLUE_KEYS.get(RoomCode.AI), CLUE_KEYS.get(RoomCode.LINUX), CLUE_KEYS.get(RoomCode.HARDWARE)));
    assertEquals(false, shuffled.json("$.accepted"), "each field must match its own room");
    assertEquals((String) wrong.json("$.message"), shuffled.json("$.message"));
    assertEquals("UNLOCK", view(alpha).json("$.screen"));
    assertEquals(2, count("SELECT count(*) FROM final_unlock_attempt WHERE team_id = " + alpha.id() + " AND NOT correct"));
    assertEquals(4, (int) view(alpha).json("$.unlock.roomNames.length()"));
    assertEquals(List.of("AI", "Problem solving", "Linux", "Hardware"), view(alpha).json("$.unlock.roomNames"));
  }

  @Test
  void theKeysAreNormalizedLikeClueAnswers() {
    openFinal();
    Response ok = expectOk(unlock(alpha, "  white   van ", "red clay", "West Tower", "+970-59-881"));
    assertEquals(true, ok.json("$.accepted"));
    assertEquals("MISSION", view(alpha).json("$.screen"));
    expectError(unlockWithKeys(alpha), 409, "The case is already unlocked.");
  }

  @Test
  void aTeamMustHoldTheCluesItsCodesBelongTo() {
    openFinal();
    // Bravo knows every key but never collected the Hardware clue: nothing opens before the Recovery Challenge.
    expectError(unlockWithKeys(bravo), 409, "Solve the Recovery Challenge first.");
    expectOk(post("/admin/overrides/clue", admin, "{\"teamId\":%d,\"roomId\":%d,\"action\":\"REVOKE\",\"reason\":\"Taken back for the test\"}".formatted(alpha.id(), rooms.get(RoomCode.AI))));
    expectError(unlockWithKeys(alpha), 409, "Solve the Recovery Challenge first.");
    assertEquals(0, count("SELECT count(*) FROM final_unlock_attempt"));
  }

  @Test
  void fiveWrongUnlocksInARowStartAThirtySecondWait() {
    openFinal();
    for (int i = 1; i <= 4; i++) {
      Response wrong = expectOk(unlock(alpha, "a", "b", "c", "d"));
      assertEquals(0, (int) wrong.json("$.waitSeconds"), "attempt " + i);
    }
    Response fifth = expectOk(unlock(alpha, "a", "b", "c", "d"));
    assertEquals(30, (int) fifth.json("$.waitSeconds"));
    assertEquals(30, (int) view(alpha).json("$.waitSeconds"));
    Response waiting = unlockWithKeys(alpha);
    assertEquals(429, waiting.status());
    assertTrue(waiting.detail().startsWith("Too many wrong answers. Wait 30 seconds"), waiting.detail());
    assertEquals(5, count("SELECT count(*) FROM final_unlock_attempt"), "a refused attempt is not an attempt");
    clock.advance(Duration.ofSeconds(29));
    assertEquals(429, unlockWithKeys(alpha).status());
    clock.advance(Duration.ofSeconds(1));
    assertEquals(true, expectOk(unlockWithKeys(alpha)).json("$.accepted"));
  }

  // ---- mission, layers --------------------------------------------------------------------------

  @Test
  void theMissionComesBeforeTheLayersAndStartingTheCaseOpensLayerOne() {
    openFinal();
    expectError(startCase(alpha), 409, "Open the case first.");
    expectOk(unlockWithKeys(alpha));
    Response mission = view(alpha);
    assertEquals("MISSION", mission.json("$.screen"));
    assertEquals("Rami left four encrypted evidence layers. Solve RSA on paper, examine the clues, and verify the signed reports.", mission.json("$.mission.story"));
    assertEquals("Printed dossiers and four shuffled key cards. A plain calculator is allowed.", mission.json("$.mission.equipment"));
    assertNull(mission.json("$.layer"));
    expectError(layer(alpha, 1, "52"), 409, "Start the case first.");
    expectOk(startCase(alpha));
    expectOk(startCase(alpha));
    Response first = view(alpha);
    assertEquals("LAYER", first.json("$.screen"));
    assertEquals(Set.of("layer", "c", "n", "solvedLayers"), ((Map<?, ?>) first.json("$.layer")).keySet(), "only the ciphertext and the modulus");
    assertEquals(1, (int) first.json("$.layer.layer"));
    assertEquals(26, (int) first.json("$.layer.c"));
    assertEquals(91, (int) first.json("$.layer.n"));
    assertEquals(0, (int) first.json("$.notebook.length()"));
  }

  @Test
  void layersOpenInOrderAndACorrectIntegerOpensTheNextEvidence() {
    openFinal();
    playTo(alpha, 0);
    expectError(layer(alpha, 2, "17"), 409, "Solve the earlier layers first.");
    expectError(layer(alpha, 3, "8"), 409, "Solve the earlier layers first.");
    expectError(layer(alpha, 5, "8"), 404, "Layer not found.");
    expectError(layer(alpha, 1, "twelve"), 400, "Enter a whole number.");
    expectError(layer(alpha, 1, "-52"), 400, "Enter a whole number.");
    Response wrong = expectOk(layer(alpha, 1, "53"));
    assertEquals(false, wrong.json("$.accepted"));
    assertEquals("That number does not open this layer.", wrong.json("$.message"));
    assertFalse(wrong.body().contains("52"), "the value is never revealed");
    assertEquals(Set.of("accepted", "message", "waitSeconds", "status", "screen"), ((Map<?, ?>) wrong.json("$")).keySet());

    expectOk(layer(alpha, 1, " 52 "));
    Response second = view(alpha);
    assertEquals(2, (int) second.json("$.layer.layer"));
    assertEquals(52, (int) second.json("$.layer.c"));
    assertEquals(77, (int) second.json("$.layer.n"));
    assertEquals(List.of("E01"), second.json("$.notebook[*].id"));
    assertEquals("At 11:32 PM, someone entered Rami's building using an authorized access card.", second.json("$.notebook[0].text"));
    expectError(layer(alpha, 1, "52"), 409, "This layer is already solved.");

    expectOk(layer(alpha, 2, "17"));
    assertEquals(List.of("E01", "E02"), view(alpha).json("$.notebook[*].id"));
    expectOk(layer(alpha, 3, "8"));
    Response third = view(alpha);
    assertEquals(List.of("E01", "E02", "E03"), third.json("$.notebook[*].id"));
    assertEquals("Rami Al-Khatib", third.json("$.notebook[2].claimedSigner"));
    assertEquals(Map.of("s", 10, "e", 7, "n", 33, "h", 17), third.json("$.notebook[2].signature"));
    assertEquals(true, third.json("$.notebook[2].verdictOpen"));
    assertNull(third.json("$.notebook[0].signature"));
    expectOk(layer(alpha, 4, "17"));
    Response all = view(alpha);
    assertEquals("SUSPECTS", all.json("$.screen"));
    assertEquals(List.of("E01", "E02", "E03", "E04"), all.json("$.notebook[*].id"));
    assertNull(all.json("$.layer"));
    assertEquals(6, (int) all.json("$.suspects.length()"));
    assertEquals(List.of("Kareem Shehab", "Leila Nasser", "Tariq Al-Khatib", "Nadia Murad", "Farid Mansour", "Mazen Haddad"), all.json("$.suspects[*].name"));
    assertEquals(5, count("SELECT count(*) FROM final_layer_attempt WHERE team_id = " + alpha.id()), "every attempt is stored");
    assertEquals(1, count("SELECT count(*) FROM final_layer_attempt WHERE team_id = " + alpha.id() + " AND NOT correct"));
  }

  @Test
  void fiveWrongAnswersInARowOnALayerStartAThirtySecondWaitForThatLayerOnly() {
    openFinal();
    playTo(alpha, 1);
    for (int i = 1; i <= 4; i++) assertEquals(0, (int) expectOk(layer(alpha, 2, "1" + i)).json("$.waitSeconds"));
    assertEquals(30, (int) expectOk(layer(alpha, 2, "19")).json("$.waitSeconds"));
    Response waiting = layer(alpha, 2, "17");
    assertEquals(429, waiting.status());
    assertTrue(waiting.detail().startsWith("Too many wrong answers. Wait 30 seconds"), waiting.detail());
    assertEquals(30, (int) view(alpha).json("$.waitSeconds"));
    assertEquals(6, count("SELECT count(*) FROM final_layer_attempt WHERE team_id = " + alpha.id()), "layer 1 right + five wrong, the refused one is not stored");
    clock.advance(Duration.ofSeconds(30));
    assertEquals(0, (int) view(alpha).json("$.waitSeconds"));
    assertEquals(true, expectOk(layer(alpha, 2, "17")).json("$.accepted"));
  }

  @Test
  void aLayerRetryWithTheSameKeyIsNotCountedTwice() {
    openFinal();
    playTo(alpha, 0);
    UUID key = UUID.randomUUID();
    String body = "{\"value\":\"40\"}";
    for (int i = 0; i < 6; i++) assertEquals(false, expectOk(teamPost(alpha, "/team/final/layers/1", key, body)).json("$.accepted"));
    assertEquals(1, count("SELECT count(*) FROM final_layer_attempt WHERE team_id = " + alpha.id()));
    expectError(teamPost(alpha, "/team/final/layers/1", key, "{\"value\":\"41\"}"), 409, "This Idempotency-Key was already used for a different request.");
  }

  // ---- verdicts and the suspect -----------------------------------------------------------------

  @Test
  void aVerdictIsOneTimeNeverRevealsCorrectnessAndNeverBlocksProgress() {
    TestTeam echo = team("Echo", GroupCode.C);
    clues(echo, RoomCode.values());
    openFinal();
    playTo(alpha, 2);
    playTo(echo, 2);
    expectError(verdict(alpha, 3, "NO"), 409, "Solve the layer first.");
    expectError(verdict(alpha, 2, "NO"), 404, "Verdict not found.");
    expectError(verdict(alpha, 3, "MAYBE"), 400, "Please check the form fields.");
    expectOk(layer(alpha, 3, "8"));
    expectOk(layer(echo, 3, "8"));
    // Alpha is wrong (E03 does not verify, the right verdict is NO), Echo is right.
    Response wrong = expectOk(verdict(alpha, 3, "YES"));
    Response right = expectOk(verdict(echo, 3, "NO"));
    assertEquals(right.body(), wrong.body(), "the answer is identical whether or not the verdict was right");
    assertEquals(Set.of("accepted", "message", "waitSeconds", "status", "screen"), ((Map<?, ?>) wrong.json("$")).keySet());
    assertEquals("YES", view(alpha).json("$.notebook[2].yourVerdict"));
    assertEquals(false, view(alpha).json("$.notebook[2].verdictOpen"));
    expectOk(verdict(alpha, 3, "YES"));
    expectError(verdict(alpha, 3, "NO"), 409, "Your verdict for this report is already recorded.");
    assertEquals(1, count("SELECT count(*) FROM final_verdict WHERE team_id = " + alpha.id()));
    // A wrong verdict blocks nothing: layer 4 still opens.
    assertEquals(true, expectOk(layer(alpha, 4, "17")).json("$.accepted"));
    assertEquals(false, db.queryForObject("SELECT correct FROM final_verdict WHERE team_id = ? AND layer = 3", Boolean.class, alpha.id()));
    assertEquals(true, db.queryForObject("SELECT correct FROM final_verdict WHERE team_id = ? AND layer = 3", Boolean.class, echo.id()));
  }

  @Test
  void theSuspectIsOneTimeAndTheSubmittedScreenNeverRevealsCorrectness() {
    TestTeam echo = team("Echo", GroupCode.C);
    clues(echo, RoomCode.values());
    openFinal();
    playTo(alpha, 3);
    expectError(suspect(alpha, LEILA), 409, "Solve all four layers first.");
    expectOk(layer(alpha, 4, "17"));
    playTo(echo, 4);
    expectError(suspect(alpha, 0), 400, "Choose one of the listed suspects.");
    expectError(suspect(alpha, 7), 400, "Choose one of the listed suspects.");
    Response right = expectOk(suspect(alpha, LEILA));
    Response wrong = expectOk(suspect(echo, KAREEM));
    assertEquals(right.body(), wrong.body(), "correctness is never revealed");
    assertEquals("SUBMITTED", right.json("$.screen"));
    assertEquals("Your final answer is recorded. The result stays confidential until judging.", right.json("$.message"));
    Response done = view(alpha);
    assertEquals("COMPLETED", done.json("$.status"));
    assertEquals("SUBMITTED", done.json("$.screen"));
    assertEquals("Alpha", done.json("$.submitted.teamName"));
    assertEquals("COMPLETED", done.json("$.submitted.status"));
    assertNotNull(done.json("$.submitted.at"));
    assertEquals("Your final answer is recorded. The result stays confidential until judging.", done.json("$.submitted.message"));
    assertEquals(0, (int) done.json("$.suspects.length()"), "the suspects are not offered again");
    assertEquals(true, expectOk(suspect(alpha, LEILA)).json("$.accepted"), "the same choice again is the same answer");
    expectError(suspect(alpha, KAREEM), 409, "Your final answer is already recorded.");
    expectError(layer(alpha, 4, "17"), 409, "Your final is already over.");
    expectError(verdict(alpha, 4, "YES"), 409, "Your final is already over.");
    assertEquals(1, count("SELECT count(*) FROM team_final WHERE team_id = " + alpha.id() + " AND finalised_cause = 'SUBMITTED'"));
  }

  @Test
  void concurrentSuspectSubmissionsRecordExactlyOne() throws Exception {
    openFinal();
    playTo(alpha, 4);
    List<Callable<Integer>> calls = new ArrayList<>();
    for (int id = 1; id <= 6; id++) {
      int choice = id;
      calls.add(() -> suspect(alpha, choice).status());
    }
    List<Integer> statuses = runTogether(calls);
    assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
    assertEquals(5, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
    assertEquals(1, count("SELECT count(*) FROM team_final WHERE team_id = " + alpha.id() + " AND suspect_id IS NOT NULL AND finalised_cause = 'SUBMITTED'"));
  }

  @Test
  void concurrentVerdictsRecordExactlyOne() throws Exception {
    openFinal();
    playTo(alpha, 3);
    List<Callable<Integer>> calls = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      String choice = i % 2 == 0 ? "YES" : "NO";
      calls.add(() -> verdict(alpha, 3, choice).status());
    }
    List<Integer> statuses = runTogether(calls);
    assertEquals(3, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
    assertEquals(3, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
    assertEquals(1, count("SELECT count(*) FROM final_verdict WHERE team_id = " + alpha.id()));
  }

  // ---- score ------------------------------------------------------------------------------------

  @Test
  void aPerfectFinalScoresFortyAndTimePointsFollowTheActiveMinutes() {
    TestTeam echo = team("Echo", GroupCode.C);
    TestTeam foxtrot = team("Foxtrot", GroupCode.D);
    clues(echo, RoomCode.values());
    clues(foxtrot, RoomCode.values());
    openFinal();
    for (TestTeam team : List.of(alpha, echo, foxtrot)) {
      playTo(team, 4);
      expectOk(verdict(team, 3, "NO"));
      expectOk(verdict(team, 4, "YES"));
    }
    clock.advance(Duration.ofMinutes(25));
    expectOk(suspect(fresh(alpha), LEILA));
    clock.advance(Duration.ofMinutes(5));
    expectOk(suspect(fresh(echo), LEILA));
    clock.advance(Duration.ofMinutes(9));
    expectOk(suspect(fresh(foxtrot), KAREEM));
    admin = adminToken();
    assertEquals(new BigDecimal("40.00"), finalPart("Alpha", "total"));
    assertEquals(new BigDecimal("15.00"), finalPart("Alpha", "time"));
    assertEquals(new BigDecimal("35.00"), finalPart("Echo", "total"), "30 active minutes: 10 time points");
    assertEquals(new BigDecimal("10.00"), finalPart("Echo", "time"));
    assertEquals(new BigDecimal("15.00"), finalPart("Echo", "crypto"));
    assertEquals(new BigDecimal("0.00"), finalPart("Foxtrot", "suspect"));
    assertEquals(new BigDecimal("15.00"), finalPart("Foxtrot", "crypto"));
    assertEquals(new BigDecimal("1.00"), finalPart("Foxtrot", "time"), "39 active minutes: 40 - 39");
    assertEquals(1500, (int) db.queryForObject("SELECT active_seconds FROM team_final WHERE team_id = ?", Integer.class, alpha.id()));
  }

  @Test
  void timePointsNeedAllFourLayersAndASubmittedSuspect() {
    openFinal();
    playTo(alpha, 3);
    expectOk(verdict(alpha, 3, "NO"));
    assertEquals(new BigDecimal("0.00"), finalPart("Alpha", "time"));
    assertEquals(new BigDecimal("10.00"), finalPart("Alpha", "crypto"), "three layers and one verdict: 7.5 + 2.5");
    assertEquals(new BigDecimal("10.00"), finalPart("Alpha", "total"));
    // Time up with the suspect unanswered: unanswered scores 0, answered parts keep their points.
    clock.advance(Duration.ofMinutes(41));
    admin = adminToken();
    assertEquals(new BigDecimal("0.00"), finalPart("Alpha", "time"));
    assertEquals(new BigDecimal("0.00"), finalPart("Alpha", "suspect"));
    assertEquals(new BigDecimal("10.00"), finalPart("Alpha", "total"));
  }

  @Test
  void pausedMinutesDoNotCountAsActiveTimeAndAddedTimeAppliesToEveryone() {
    TestTeam echo = team("Echo", GroupCode.C);
    clues(echo, RoomCode.values());
    openFinal();
    for (TestTeam team : List.of(alpha, echo)) {
      playTo(team, 4);
      expectOk(verdict(team, 3, "NO"));
      expectOk(verdict(team, 4, "YES"));
    }
    clock.advance(Duration.ofMinutes(5));
    admin = adminToken();
    Response paused = expectOk(post("/admin/final/timer/pause", admin, null));
    assertEquals("PAUSED", paused.json("$.phase"));
    assertEquals(2100, (int) paused.json("$.remainingSeconds"));
    clock.advance(Duration.ofMinutes(10));
    assertEquals(2100, (int) view(fresh(alpha)).json("$.remainingSeconds"));
    assertEquals(2100, (int) view(fresh(echo)).json("$.remainingSeconds"));
    expectError(suspect(fresh(alpha), LEILA), 409, "The final is paused.");
    admin = adminToken();
    expectOk(post("/admin/final/timer/resume", admin, null));
    UUID key = UUID.randomUUID();
    Response added = expectOk(call(org.springframework.http.HttpMethod.POST, "/admin/final/timer/add", admin, "{\"seconds\":300}", key.toString()));
    assertEquals(2400, (int) added.json("$.remainingSeconds"));
    expectOk(call(org.springframework.http.HttpMethod.POST, "/admin/final/timer/add", admin, "{\"seconds\":300}", key.toString()));
    assertEquals(2400, (int) expectOk(get("/admin/final/timer", admin)).json("$.remainingSeconds"), "a repeated key adds the time once");
    clock.advance(Duration.ofMinutes(20));
    expectOk(suspect(fresh(alpha), LEILA));
    admin = adminToken();
    assertEquals(1500, (int) db.queryForObject("SELECT active_seconds FROM team_final WHERE team_id = ?", Integer.class, alpha.id()), "5 + 20 active minutes; the 10 paused ones are not counted");
    assertEquals(new BigDecimal("40.00"), finalPart("Alpha", "total"));
    // Everyone shares the one timer.
    assertEquals((int) view(fresh(alpha)).json("$.remainingSeconds"), (int) view(fresh(echo)).json("$.remainingSeconds"));
    List<String> actions = db.queryForList("SELECT action FROM audit_event WHERE action LIKE 'EVENT_FINAL_%' ORDER BY id", String.class);
    assertTrue(actions.containsAll(List.of("EVENT_FINAL_PAUSED", "EVENT_FINAL_RESUMED", "EVENT_FINAL_TIME_ADDED")), actions.toString());
    assertEquals(403, post("/admin/final/timer/pause", fresh(alpha).token(), null).status());
    assertEquals(403, post("/admin/final/timer/pause", tokenOfUser(managerIds.get(RoomCode.AI)), null).status());
    expectError(call(org.springframework.http.HttpMethod.POST, "/admin/final/timer/add", admin, "{\"seconds\":0}", UUID.randomUUID().toString()), 400, "Please check the form fields.");
  }

  // ---- time up ----------------------------------------------------------------------------------

  @Test
  void whenTimeIsUpInputStopsAndTheTeamIsFinalisedExactlyOnce() {
    openFinal();
    playTo(alpha, 3);
    clock.advance(Duration.ofMinutes(41));
    TestTeam later = fresh(alpha);
    Response refused = layer(later, 4, "17");
    expectError(refused, 409, "Time is up for the final.");
    assertEquals(1, count("SELECT count(*) FROM team_final WHERE team_id = " + alpha.id() + " AND finalised_cause = 'TIME_UP'"), "the refusal still finalised the team");
    expectError(layer(later, 4, "17"), 409, "Your final is already over.");
    expectError(suspect(later, LEILA), 409, "Your final is already over.");
    Response view = view(later);
    assertEquals("COMPLETED", view.json("$.status"));
    assertEquals("SUBMITTED", view.json("$.screen"));
    assertEquals("Time is up. The final is over for your team.", view.json("$.submitted.message"));
    assertEquals(3, count("SELECT count(*) FROM final_layer_attempt WHERE team_id = " + alpha.id() + " AND correct"));
    assertEquals(1, count("SELECT count(*) FROM team_final WHERE team_id = " + alpha.id()));
    assertEquals(0, (int) view(fresh(bravo)).json("$.remainingSeconds"));
    admin = adminToken();
    expectError(
      call(org.springframework.http.HttpMethod.POST, "/admin/final/timer/add", admin, "{\"seconds\":300}", UUID.randomUUID().toString()),
      409,
      "Time is up for the final; its timer can no longer change."
    );
  }

  @Test
  void aTeamThatNeverStartedIsAlsoFinalisedAtTimeUp() {
    openFinal();
    clock.advance(Duration.ofMinutes(41));
    assertEquals("COMPLETED", view(fresh(alpha)).json("$.status"));
    assertEquals("COMPLETED", view(fresh(bravo)).json("$.status"));
    assertEquals(0, count("SELECT count(*) FROM team_final"), "reads never write");
    expectError(unlockWithKeys(fresh(alpha)), 409, "Time is up for the final.");
    assertEquals(1, count("SELECT count(*) FROM team_final"));
  }

  @Test
  void timeUpRacingSubmissionsAndTheAdminCloseFinalisesEachTeamOnce() throws Exception {
    List<TestTeam> racers = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      TestTeam racer = team("Racer " + i, GroupCode.values()[i % 4]);
      clues(racer, RoomCode.values());
      racers.add(racer);
    }
    openFinal();
    for (TestTeam racer : racers) playTo(racer, 4);
    clock.advance(Duration.ofMinutes(41));
    admin = adminToken();
    List<TestTeam> current = racers.stream().map(this::fresh).toList();
    List<Callable<Integer>> calls = new ArrayList<>();
    for (TestTeam racer : current) calls.add(() -> suspect(racer, LEILA).status());
    String adminNow = admin;
    calls.add(() -> post("/admin/final/close", adminNow, null).status());
    runTogether(calls);
    for (TestTeam racer : racers) {
      assertEquals(1, count("SELECT count(*) FROM team_final WHERE team_id = " + racer.id()), racer.name());
      assertEquals("TIME_UP", db.queryForObject("SELECT finalised_cause FROM team_final WHERE team_id = ?", String.class, racer.id()), racer.name());
      assertNull(db.queryForObject("SELECT suspect_id FROM team_final WHERE team_id = ?", Integer.class, racer.id()), "no answer after the time is up: " + racer.name());
    }
  }

  @Test
  void aSubmissionRacingTheAdminCloseIsEitherRecordedOrRefusedNeverBoth() throws Exception {
    List<TestTeam> racers = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      TestTeam racer = team("Racer " + i, GroupCode.values()[i % 4]);
      clues(racer, RoomCode.values());
      racers.add(racer);
    }
    openFinal();
    for (TestTeam racer : racers) playTo(racer, 4);
    List<Callable<Integer>> calls = new ArrayList<>();
    for (TestTeam racer : racers) calls.add(() -> suspect(racer, LEILA).status());
    calls.add(() -> post("/admin/final/close", admin, null).status());
    List<Integer> statuses = runTogether(calls);
    assertEquals(200, statuses.getLast(), statuses.toString());
    for (TestTeam racer : racers) {
      var row = db.queryForMap("SELECT suspect_id, finalised_cause FROM team_final WHERE team_id = ?", racer.id());
      assertEquals(1, count("SELECT count(*) FROM team_final WHERE team_id = " + racer.id()), racer.name());
      assertEquals("SUBMITTED".equals(row.get("finalised_cause")), row.get("suspect_id") != null, racer.name() + " " + row);
      assertTrue(Set.of("SUBMITTED", "CLOSED").contains(row.get("finalised_cause")), racer.name() + " " + row);
    }
  }

  @Test
  void closingTheFinalFinalisesEveryoneWhoTookPartOnceAndItCannotReopen() {
    TestTeam charlie = team("Charlie", GroupCode.C);
    clues(charlie, RoomCode.AI);
    openFinal();
    playTo(alpha, 2);
    expectOk(post("/admin/final/close", admin, null));
    assertEquals("COMPLETED", view(alpha).json("$.status"));
    assertEquals("COMPLETED", view(bravo).json("$.status"));
    assertEquals("NOT_QUALIFIED", view(charlie).json("$.status"));
    assertEquals(2, count("SELECT count(*) FROM team_final"));
    assertEquals(2, count("SELECT count(*) FROM team_final WHERE finalised_cause = 'CLOSED'"));
    expectError(post("/admin/final/close", admin, null), 409, "The final is not open.");
    expectError(post("/admin/final/open", admin, null), 409, "The final was already opened once.");
    expectError(layer(alpha, 3, "8"), 409, "The final is not open.");
    expectError(recover(bravo, UUID.randomUUID(), RECOVERY_ANSWER), 409, "The final is not open.");
  }

  // ---- content ----------------------------------------------------------------------------------

  @Test
  void importingTheContentIsAdminOnlyValidatedWholeAndLockedOnceTheFinalOpens() {
    String good = finalContentJson();
    assertEquals(403, put("/admin/final/content", alpha.token(), good).status());
    assertEquals(403, put("/admin/final/content", managers.get(RoomCode.AI), good).status());
    assertEquals(401, put("/admin/final/content", null, good).status());
    assertEquals(403, get("/admin/final/content", alpha.token()).status());
    String stored = db.queryForObject("SELECT content::text FROM final_content", String.class);

    Response broken = importFinalContent(good.replace("\"n\": 91", "\"n\": 92").replace("\"correctSuspect\": \"Leila Nasser\"", "\"correctSuspect\": \"Nobody\""));
    assertEquals(400, broken.status());
    List<String> problems = broken.json("$.problems");
    assertTrue(problems.contains("Layer 1: n must equal p x q."), problems.toString());
    assertTrue(problems.contains("correctSuspect: must be one of the six suspects."), problems.toString());
    assertEquals("The file has " + problems.size() + " problems.", broken.detail());
    assertEquals(400, importFinalContent("{{{").status());
    assertEquals(400, importFinalContent("").status());
    assertEquals(400, importFinalContent(good.replace("\"format\": 1,", "\"format\": 1, \"extra\": 1,")).status(), "unknown fields are refused");
    assertEquals(stored, db.queryForObject("SELECT content::text FROM final_content", String.class), "a refused import changes nothing");

    String shipped = good.replace(RECOVERY_QUESTION, "PLACEHOLDER - question").replace(RECOVERY_ANSWER, "PLACEHOLDER-X");
    Response imported = expectOk(importFinalContent(shipped));
    assertEquals(true, imported.json("$.recoveryIsPlaceholder"));
    assertEquals(25, (int) imported.json("$.fullUntilMinutes"));
    assertTrue(expectOk(get("/admin/board", admin)).<List<String>>json("$.alerts[*].code").contains("RECOVERY_PLACEHOLDER"));
    expectOk(importFinalContent(good));
    assertFalse(expectOk(get("/admin/board", admin)).<List<String>>json("$.alerts[*].code").contains("RECOVERY_PLACEHOLDER"));

    openFinal();
    expectError(importFinalContent(good), 409, "Event content is read-only after the final opens.");
    assertEquals(true, expectOk(get("/admin/final/content", admin)).json("$.readOnly"));
  }

  @Test
  void aMemberNeverReceivesPrivateValuesVerdictsTheCorrectSuspectOrScoresAtAnyStep() {
    TestTeam echo = team("Echo", GroupCode.C);
    clues(echo, RoomCode.values());
    List<String> bodies = new ArrayList<>();
    List<TestTeam> everyone = List.of(alpha, bravo, echo);
    Runnable snapshot = () -> {
      for (TestTeam team : everyone) {
        for (String path : List.of("/team/final", "/team/board", "/home")) bodies.add(expectOk(get(path, team.token())).body());
      }
    };
    snapshot.run();
    openFinal();
    snapshot.run();
    bodies.add(expectOk(recover(bravo, UUID.randomUUID(), "nope")).body());
    bodies.add(expectOk(recover(bravo, UUID.randomUUID(), RECOVERY_ANSWER)).body());
    bodies.add(expectOk(unlock(alpha, "a", "b", "c", "d")).body());
    bodies.add(expectOk(unlockWithKeys(alpha)).body());
    snapshot.run();
    bodies.add(expectOk(startCase(alpha)).body());
    for (int n = 1; n <= 4; n++) {
      bodies.add(expectOk(layer(alpha, n, "1")).body());
      bodies.add(expectOk(layer(alpha, n, Long.toString(LAYER_ANSWERS[n - 1]))).body());
      snapshot.run();
    }
    bodies.add(expectOk(verdict(alpha, 3, "NO")).body());
    bodies.add(expectOk(verdict(alpha, 4, "YES")).body());
    bodies.add(expectOk(suspect(alpha, LEILA)).body());
    snapshot.run();
    expectOk(put("/admin/publication", admin, "{\"published\":true}"));
    snapshot.run();
    for (String body : bodies) {
      assertFalse(FORBIDDEN_FIELDS.matcher(body).find(), "a private field leaked: " + body);
      assertFalse(body.contains("\"verdict\""), "no verdict field: " + body);
      assertFalse(body.contains("acceptedAnswers") || body.contains(RECOVERY_ANSWER), body);
    }
    // The published ranking shows scores and nothing else about how they were made.
    String published = expectOk(get("/results", alpha.token())).body();
    assertFalse(published.contains("deduction") || published.contains("cheating") || published.contains("hints"), published);
  }

  // ---- statements -------------------------------------------------------------------------------

  @Test
  void theTeamAndAdminReadEndpointsUseTheSameStatementsForTwoAndForFourteenTeams() {
    openFinal();
    playTo(alpha, 2);
    expectOk(put("/rooms/" + rooms.get(RoomCode.HARDWARE) + "/manual-scores/" + alpha.id(), managers.get(RoomCode.HARDWARE), "{\"score\":12}"));
    expectOk(post("/rooms/" + rooms.get(RoomCode.AI) + "/hints", admin, "{\"teamId\":" + alpha.id() + "}"));
    Map<String, java.util.function.Supplier<Response>> reads = new java.util.LinkedHashMap<>();
    reads.put("team final", () -> get("/team/final", alpha.token()));
    reads.put("team board", () -> get("/team/board", alpha.token()));
    reads.put("admin board", () -> get("/admin/board", admin));
    reads.put("admin scores", () -> get("/admin/scores", admin));
    reads.put("results (admin)", () -> get("/results", admin));
    reads.put("room board", () -> get("/rooms/" + rooms.get(RoomCode.HARDWARE) + "/board", managers.get(RoomCode.HARDWARE)));
    reads.put("room log", () -> get("/rooms/" + rooms.get(RoomCode.AI) + "/deductions", managers.get(RoomCode.AI)));
    reads.put("export", () -> get("/admin/export.csv", admin));
    Map<String, List<String>> few = new java.util.LinkedHashMap<>();
    reads.forEach((name, read) -> few.put(name, statementsOf(read)));
    assertEquals(2, count("SELECT count(*) FROM event_team"));

    int n = 0;
    for (TestTeam extra : teams(3)) {
      n++;
      clues(extra, n % 3 == 0 ? RoomCode.values() : new RoomCode[] { RoomCode.AI, RoomCode.LINUX, RoomCode.PROBLEM_SOLVING });
      if (n % 3 == 0) playTo(extra, n % 2);
      if (n % 4 == 0) expectOk(post("/rooms/" + rooms.get(RoomCode.AI) + "/cheating", admin, "{\"teamId\":%d,\"reason\":\"Looked at notes\",\"confirmed\":true}".formatted(extra.id())));
    }
    assertEquals(14, count("SELECT count(*) FROM event_team"));
    StringBuilder report = new StringBuilder("FINAL STATEMENTS (2 teams / 14 teams):");
    for (String name : reads.keySet()) {
      List<String> many = statementsOf(reads.get(name));
      assertEquals(few.get(name), many, name);
      report.append(" ").append(name).append(" = ").append(few.get(name).size()).append("/").append(many.size()).append(";");
    }
    System.out.println(report);
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private Response view(TestTeam team) {
    return expectOk(get("/team/final", team.token()));
  }

  /** Gives the team these rooms' clues directly (the collection itself is tested with the rooms). */
  private void clues(TestTeam team, RoomCode... codes) {
    for (RoomCode code : codes) {
      db.update("INSERT INTO collected_clue(team_id, room_id, source, collected_at) VALUES (?, ?, 'MANUAL', now()) ON CONFLICT DO NOTHING", team.id(), rooms.get(code));
    }
  }

  private int count(String sql) {
    return db.queryForObject(sql, Integer.class);
  }

  /** A new session for the team (the manual clock moved past the old token's life). */
  private TestTeam fresh(TestTeam team) {
    String email = db.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, team.accountId());
    return new TestTeam(team.id(), team.name(), team.group(), team.accountId(), login(email));
  }

  private String tokenOfUser(long userId) {
    return login(db.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, userId));
  }

  private String adminToken() {
    return login(db.queryForObject("SELECT email FROM app_user WHERE role = 'ADMIN' LIMIT 1", String.class));
  }

  /** One part of the team's final score from the admin's breakdown. */
  private BigDecimal finalPart(String teamName, String part) {
    List<Number> found = expectOk(get("/admin/scores", admin)).json("$[?(@.teamName == '" + teamName + "')].finalScore." + part);
    return new BigDecimal(found.get(0).toString()).setScale(2);
  }

  /** Starts every call at the same moment and returns their results in order. */
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

  /** The SQL texts of one request without the session check, repeats collapsed; made once first to warm caches. */
  private List<String> statementsOf(java.util.function.Supplier<Response> request) {
    expectOk(request.get());
    clearInvocations(spiedJdbc);
    expectOk(request.get());
    List<String> statements = new ArrayList<>();
    for (Invocation invocation : mockingDetails(spiedJdbc).getInvocations()) {
      Object[] args = invocation.getArguments();
      if (args.length == 0 || !(args[0] instanceof String sql) || sql.contains("auth_session")) continue;
      if (statements.isEmpty() || !statements.getLast().equals(sql)) statements.add(sql);
    }
    return statements;
  }
}
