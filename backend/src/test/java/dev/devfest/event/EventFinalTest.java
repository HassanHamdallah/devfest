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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The final "The Last Shipment": every team plays (recovery first with fewer than 4 keys, see EventFinalRecoveryTest),
 * unlock, the four evidence files and their passphrases, each team's own phases, the one-time suspect, the single 40-minute
 * timer, time up, scoring and what a member may ever see.
 */
@Import(ManualClock.Config.class)
class EventFinalTest extends EventIntegrationTest {

  private static final String RECOVERY_FIRST = "Finish your recovery levels first.";
  private static final String WRONG_PASSPHRASE = "That passphrase does not open this clue.";
  /** One line of each report that appears nowhere else (REPORT_MARKS[n - 1] belongs to clue n). */
  private static final List<String> REPORT_MARKS = List.of("SHP-NTC-0412", "Visitor badge V-221", "Forklift FL-3", "Badge E-4412");
  /** Field names that must never appear in a member response (the private values, correctness, scores). */
  private static final Pattern FORBIDDEN_FIELDS = Pattern.compile(
    "\"(passphraseSha256|passphrase|correctSuspect|suspectCorrect|acceptedAnswers|kept|order|answer|correct|bugs|requirement|score|scores|total|rank|finalScore|roomScores|cluePoints)\"\\s*:"
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
  void everyTeamPlaysAndItsPathDependsOnTheKeysItHeldWhenTheFinalOpened() {
    TestTeam charlie = team("Charlie", GroupCode.C);
    TestTeam delta = team("Delta", GroupCode.D);
    clues(charlie, RoomCode.AI, RoomCode.LINUX);
    assertEquals("LOCKED", view(alpha).json("$.status"));
    assertEquals("LOCKED", view(alpha).json("$.screen"));
    expectError(unlockWithKeys(alpha), 409, "The final is not open.");

    openFinal();
    assertEquals(List.of(4, 3, 2, 0), List.of(alpha, bravo, charlie, delta).stream().map(t -> count("SELECT keys_at_open FROM team_recovery WHERE team_id = " + t.id())).toList());
    assertEquals("IN_FINAL", view(alpha).json("$.status"));
    assertEquals("UNLOCK", view(alpha).json("$.screen"));
    assertNull(view(alpha).json("$.recovery"));
    assertEquals(List.of(), view(alpha).json("$.unlock.recoveredKeys"));
    Map<TestTeam, List<String>> paths = Map.of(bravo, List.of("ML"), charlie, List.of("ML", "ARDUINO"), delta, List.of("ML", "ARDUINO"));
    paths.forEach((team, levels) -> {
      Response v = view(team);
      assertEquals("RECOVERY", v.json("$.status"), team.name());
      assertEquals("RECOVERY", v.json("$.screen"), team.name());
      assertEquals(levels, v.json("$.recovery.levels"), team.name());
      assertNull(v.json("$.unlock"));
      assertNull(v.json("$.phase"), "no case clock before the recovery is finished");
      assertEquals(0, (int) v.json("$.clues.length()"));
      expectError(unlockWithKeys(team), 409, RECOVERY_FIRST);
      expectError(startCase(team), 409, "Open the case first.");
      expectError(passphrase(team, 1, PASSPHRASES.get(0)), 409, RECOVERY_FIRST);
      expectError(suspect(team, CORRECT_SUSPECT), 409, RECOVERY_FIRST);
    });
    expectError(mlCheck(alpha, 1, ML_ANSWERS.get(0)), 409, "Your team needs no recovery.");
    List<String> statuses = expectOk(get("/admin/scores", admin)).json("$[*].finalScore.status");
    assertEquals(4, statuses.size());
    assertEquals(3, statuses.stream().filter("RECOVERY"::equals).count());
    assertEquals(1, statuses.stream().filter("IN_FINAL"::equals).count());
  }

  @Test
  void aKeyGrantedAfterTheFinalOpenedDoesNotChangeThePath() {
    openFinal();
    clues(bravo, RoomCode.HARDWARE);
    assertEquals("RECOVERY", view(bravo).json("$.status"), "K was taken when the final opened");
    expectError(unlockWithKeys(bravo), 409, RECOVERY_FIRST);
    // A team activated after the opening is counted from the keys it holds at its first look.
    TestTeam late = team("Late", GroupCode.C);
    clues(late, RoomCode.values());
    assertEquals("IN_FINAL", view(late).json("$.status"));
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
    // Bravo knows every key but never collected the Hardware clue: nothing opens before its recovery.
    expectError(unlockWithKeys(bravo), 409, RECOVERY_FIRST);
    expectOk(post("/admin/overrides/clue", admin, "{\"teamId\":%d,\"roomId\":%d,\"action\":\"REVOKE\",\"reason\":\"Taken back for the test\"}".formatted(alpha.id(), rooms.get(RoomCode.AI))));
    // Alpha held four keys when the final opened (no recovery); a key revoked later must be granted again.
    expectError(unlockWithKeys(alpha), 409, "Your team does not hold all four room keys. Ask the organizer.");
    assertEquals("UNLOCK", view(alpha).json("$.screen"));
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

  // ---- mission and the case ---------------------------------------------------------------------

  @Test
  void theMissionComesBeforeTheCaseAndStartingOpensTheCase() {
    openFinal();
    expectError(startCase(alpha), 409, "Open the case first.");
    expectOk(unlockWithKeys(alpha));
    Response mission = view(alpha);
    assertEquals("MISSION", mission.json("$.screen"));
    assertEquals("THE LAST SHIPMENT", mission.json("$.mission.title"));
    assertTrue(((String) mission.json("$.mission.intro")).contains("NOOR DISAPPEARED."));
    assertEquals("Decrypt all four files (15 minutes).", mission.json("$.mission.phase1"));
    assertEquals("Analyze the decrypted information and compare it with the Suspects Sheet (25 minutes).", mission.json("$.mission.phase2"));
    assertEquals(0, (int) mission.json("$.clues.length()"), "the files come with the case");
    assertEquals(0, (int) mission.json("$.suspects.length()"));
    expectError(passphrase(alpha, 1, PASSPHRASES.get(0)), 409, "Start the case first.");
    expectError(suspect(alpha, CORRECT_SUSPECT), 409, "Start the case first.");
    assertEquals("CASE", expectOk(startCase(alpha)).json("$.screen"));
    expectOk(startCase(alpha));
    Response started = view(alpha);
    assertEquals("CASE", started.json("$.screen"));
    assertEquals("THE LAST SHIPMENT", started.json("$.mission.title"));
    assertEquals(List.of(1, 2, 3, 4), started.json("$.clues[*].number"));
    assertEquals(
      List.of("ORION_SHIPMENT_NOTICE", "SECURITY_ACCESS_REPORT", "WAREHOUSE_MOVEMENT_REPORT", "STAFF_ACTIVITY_AUDIT"),
      started.json("$.clues[*].title")
    );
    assertEquals("ORION_SHIPMENT_NOTICE.enc", started.json("$.clues[0].encFile"));
    assertEquals("clue1_key.rsa", started.json("$.clues[0].rsaFile"));
    assertEquals("NV-4K7", started.json("$.clues[0].keyReference"));
    assertEquals(
      Set.of("number", "title", "encFile", "rsaFile", "keyReference", "solved", "waitSeconds", "report"),
      ((Map<?, ?>) started.json("$.clues[0]")).keySet()
    );
    assertEquals(List.of(false, false, false, false), started.json("$.clues[*].solved"));
    assertEquals(4, (int) started.json("$.keyIndex.length()"));
    assertEquals(Set.of("A", "B", "C", "D"), Set.copyOf(started.<List<String>>json("$.keyIndex[*].key")));
    assertEquals("DECRYPTION", started.json("$.phase"));
    assertEquals(15, (int) started.json("$.phase1Minutes"));
    assertEquals(0, (int) started.json("$.suspects.length()"), "no suspects while the decryption phase runs");
    assertEquals(false, started.json("$.submitted"));
    assertNull(started.json("$.terminal"), "no final terminal package uploaded");
    openInvestigationPhase();
    Response investigating = view(alpha);
    assertEquals("INVESTIGATION", investigating.json("$.phase"));
    assertEquals(20, (int) investigating.json("$.suspects.length()"));
    assertEquals(Set.of("id", "name", "position", "statement"), ((Map<?, ?>) investigating.json("$.suspects[0]")).keySet());
    assertEquals(List.of("Samer Murad"), investigating.json("$.suspects[?(@.id == 'S11')].name"));
    assertEquals(List.of("Vendor Relations Officer"), investigating.json("$.suspects[?(@.id == 'S11')].position"));
  }

  @Test
  void aReportStaysHiddenUntilItsFileIsDecryptedAndFilesOpenInAnyOrder() {
    openFinal();
    playTo(alpha, 0);
    Response before = view(alpha);
    for (String mark : REPORT_MARKS) assertFalse(before.body().contains(mark), "no report before it is solved: " + mark);
    assertEquals(4, ((List<?>) before.json("$.clues[?(@.report == null)]")).size());

    Set<Integer> solved = new HashSet<>();
    for (int clue : new int[] { 3, 1, 4, 2 }) {
      Response right = expectOk(passphrase(alpha, clue, PASSPHRASES.get(clue - 1)));
      assertEquals(true, right.json("$.accepted"));
      assertEquals("CASE", right.json("$.screen"));
      assertEquals(Set.of("accepted", "message", "waitSeconds", "status", "screen"), ((Map<?, ?>) right.json("$")).keySet());
      solved.add(clue);
      Response now = view(alpha);
      for (int n = 1; n <= 4; n++) {
        assertEquals(solved.contains(n), now.json("$.clues[" + (n - 1) + "].solved"), "clue " + n);
        assertEquals(solved.contains(n), now.body().contains(REPORT_MARKS.get(n - 1)), "report " + n + " shown only once solved");
      }
      expectError(passphrase(alpha, clue, PASSPHRASES.get(clue - 1)), 409, "This evidence file is already decrypted.");
    }
    assertTrue(((String) view(alpha).json("$.clues[0].report")).startsWith("NOVA TECHNOLOGIES\nInternal Shipment Notification"));
    assertEquals("CASE", view(alpha).json("$.screen"), "the case stays open until the suspect is submitted");
    assertEquals(4, count("SELECT count(*) FROM final_clue_attempt WHERE team_id = " + alpha.id() + " AND correct"));
  }

  @Test
  void thePassphraseIsTrimmedAndLowerCasedBeforeItsHashIsCompared() {
    openFinal();
    playTo(alpha, 0);
    String secret1 = PASSPHRASES.get(0).strip();
    assertTrue(PASSPHRASES.get(0).endsWith("\r\n"), "the key file keeps its line ending");
    assertEquals(true, expectOk(passphrase(alpha, 1, PASSPHRASES.get(0))).json("$.accepted"), "with the trailing line ending");
    assertEquals(true, expectOk(passphrase(alpha, 2, PASSPHRASES.get(1).strip())).json("$.accepted"), "without it");
    assertEquals(true, expectOk(passphrase(alpha, 3, "  " + PASSPHRASES.get(2).strip().toUpperCase() + "\n")).json("$.accepted"), "upper case");
    Response wrongFile = expectOk(passphrase(alpha, 4, secret1));
    assertEquals(false, wrongFile.json("$.accepted"), "another file's passphrase does not open it");
    assertEquals(WRONG_PASSPHRASE, wrongFile.json("$.message"));
    Response almost = expectOk(passphrase(alpha, 4, PASSPHRASES.get(3).strip().substring(1)));
    assertEquals(wrongFile.body(), almost.body(), "the answer never says how close it was");
    assertFalse(almost.body().contains(PASSPHRASES.get(3).strip()));
    expectError(passphrase(alpha, 5, secret1), 404, "Clue not found.");
    expectError(passphrase(alpha, 0, secret1), 404, "Clue not found.");
    expectError(passphrase(alpha, 4, "   "), 400, "Please check the form fields.");
    expectError(passphrase(alpha, 4, "x".repeat(201)), 400, "Please check the form fields.");
    assertEquals(5, count("SELECT count(*) FROM final_clue_attempt WHERE team_id = " + alpha.id()), "every evaluated attempt is stored");
    assertEquals(PASSPHRASES.get(0), db.queryForObject("SELECT value FROM final_clue_attempt WHERE team_id = ? AND clue = 1", String.class, alpha.id()));
  }

  @Test
  void fiveWrongPassphrasesInARowStartAThirtySecondWaitForThatFileOnly() {
    openFinal();
    playTo(alpha, 0);
    for (int i = 1; i <= 4; i++) assertEquals(0, (int) expectOk(passphrase(alpha, 2, "wrong " + i)).json("$.waitSeconds"));
    assertEquals(30, (int) expectOk(passphrase(alpha, 2, "wrong 5")).json("$.waitSeconds"));
    Response waiting = passphrase(alpha, 2, PASSPHRASES.get(1));
    assertEquals(429, waiting.status());
    assertTrue(waiting.detail().startsWith("Too many wrong answers. Wait 30 seconds"), waiting.detail());
    Response view = view(alpha);
    assertEquals(List.of(0, 30, 0, 0), view.json("$.clues[*].waitSeconds"));
    assertEquals(0, (int) view.json("$.waitSeconds"), "the unlock's wait is separate");
    // The other files are not affected.
    assertEquals(true, expectOk(passphrase(alpha, 1, PASSPHRASES.get(0))).json("$.accepted"));
    assertEquals(0, (int) expectOk(passphrase(alpha, 3, "wrong")).json("$.waitSeconds"));
    assertEquals(7, count("SELECT count(*) FROM final_clue_attempt WHERE team_id = " + alpha.id()), "the refused one is not an attempt");
    clock.advance(Duration.ofSeconds(29));
    assertEquals(429, passphrase(fresh(alpha), 2, PASSPHRASES.get(1)).status());
    clock.advance(Duration.ofSeconds(1));
    TestTeam later = fresh(alpha);
    assertEquals(0, (int) view(later).json("$.clues[1].waitSeconds"));
    assertEquals(true, expectOk(passphrase(later, 2, PASSPHRASES.get(1))).json("$.accepted"));
  }

  @Test
  void aPassphraseRetryWithTheSameKeyIsNotCountedTwice() {
    openFinal();
    playTo(alpha, 0);
    UUID key = UUID.randomUUID();
    String body = "{\"passphrase\":\"not-it\"}";
    for (int i = 0; i < 6; i++) assertEquals(false, expectOk(teamPost(alpha, "/team/final/clues/1/passphrase", key, body)).json("$.accepted"));
    assertEquals(1, count("SELECT count(*) FROM final_clue_attempt WHERE team_id = " + alpha.id()));
    expectError(teamPost(alpha, "/team/final/clues/1/passphrase", key, "{\"passphrase\":\"other\"}"), 409, "This Idempotency-Key was already used for a different request.");
    expectError(teamPost(alpha, "/team/final/clues/2/passphrase", key, body), 409, "This Idempotency-Key was already used for a different request.");
    assertEquals(400, call(HttpMethod.POST, "/team/final/clues/1/passphrase", alpha.token(), body, null).status(), "the key is required");
  }

  // ---- the suspect ------------------------------------------------------------------------------

  @Test
  void theSuspectIsOneTimeOnTheCaseScreenAndNeverRevealsCorrectness() {
    TestTeam echo = team("Echo", GroupCode.C);
    clues(echo, RoomCode.values());
    openFinal();
    playTo(alpha, 1);
    playTo(echo, 4);
    openInvestigationPhase();
    expectError(suspect(alpha, "S99"), 400, "Choose one of the listed suspects.");
    expectError(suspect(alpha, ""), 400, "Please check the form fields.");
    Response right = expectOk(suspect(alpha, CORRECT_SUSPECT));
    Response wrong = expectOk(suspect(echo, WRONG_SUSPECT));
    assertEquals(right.body(), wrong.body(), "correctness is never revealed");
    assertEquals("SUBMITTED", right.json("$.screen"));
    assertEquals("Your final answer is recorded. The result stays confidential until judging.", right.json("$.message"));
    Response done = view(alpha);
    assertEquals("COMPLETED", done.json("$.status"));
    assertEquals("SUBMITTED", done.json("$.screen"));
    assertEquals(true, done.json("$.submitted"));
    assertEquals("Alpha", done.json("$.submission.teamName"));
    assertEquals("COMPLETED", done.json("$.submission.status"));
    assertNotNull(done.json("$.submission.at"));
    assertEquals("Your final answer is recorded. The result stays confidential until judging.", done.json("$.submission.message"));
    assertEquals(0, (int) done.json("$.suspects.length()"), "the suspects are not offered again");
    assertEquals(0, (int) done.json("$.clues.length()"));
    assertEquals(true, expectOk(suspect(alpha, CORRECT_SUSPECT)).json("$.accepted"), "the same choice again is the same answer");
    expectError(suspect(alpha, WRONG_SUSPECT), 409, "Your final answer is already recorded.");
    expectError(passphrase(alpha, 2, PASSPHRASES.get(1)), 409, "Your final is already over.");
    assertEquals(1, count("SELECT count(*) FROM team_final WHERE team_id = " + alpha.id() + " AND finalised_cause = 'SUBMITTED'"));
    assertEquals("S11", db.queryForObject("SELECT suspect_id FROM team_final WHERE team_id = ?", String.class, alpha.id()));
    assertEquals(true, db.queryForObject("SELECT suspect_correct FROM team_final WHERE team_id = ?", Boolean.class, alpha.id()));
    assertEquals(false, db.queryForObject("SELECT suspect_correct FROM team_final WHERE team_id = ?", Boolean.class, echo.id()));
  }

  // ---- the two phases ---------------------------------------------------------------------------
  // There is no clock in the phase rule: Phase 2 opens for a team only when it has decrypted all four files; Phase 1
  // never closes before the final ends (40:00). phase1Minutes (15) is only an estimate shown to the team.

  private static final String DECRYPT_FIRST = "Decrypt all four files to open the investigation.";

  @Test
  void threeFilesAreStillPhaseOneAt39m59AndTheFinalEndsAt40m() {
    openFinal();
    playTo(alpha, 3);
    for (Duration at : List.of(Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(19).plusSeconds(59))) {
      // 15:00, 20:00, 39:59 of the final's time.
      clock.advance(at);
      TestTeam later = fresh(alpha);
      assertEquals("DECRYPTION", view(later).json("$.phase"));
      assertEquals(false, expectOk(passphrase(later, 4, "wrong " + at.toSeconds())).json("$.accepted"), "clue submission is still accepted");
      expectError(suspect(later, CORRECT_SUSPECT), 409, DECRYPT_FIRST);
    }
    clock.advance(Duration.ofSeconds(1));
    TestTeam over = fresh(alpha);
    expectError(passphrase(over, 4, PASSPHRASES.get(3)), 409, "Time is up for the final.");
    expectError(suspect(over, CORRECT_SUSPECT), 409, "Your final is already over.");
    assertEquals("COMPLETED", view(over).json("$.status"));
    admin = adminToken();
    assertEquals(new BigDecimal("11.25"), finalPart("Alpha", "clues"));
    assertEquals(new BigDecimal("0.00"), finalPart("Alpha", "suspect"));
    assertEquals(new BigDecimal("0.00"), finalPart("Alpha", "time"));
    assertEquals(new BigDecimal("11.25"), finalPart("Alpha", "total"));
  }

  @Test
  void theFourthFileAtEightMinutesOpensTheInvestigationAndACorrectSuspectAtTwentyScoresForty() {
    openFinal();
    playTo(alpha, 3);
    clock.advance(Duration.ofMinutes(8));
    TestTeam at8 = fresh(alpha);
    assertEquals("DECRYPTION", view(at8).json("$.phase"));
    assertEquals(0, (int) view(at8).json("$.suspects.length()"));
    assertEquals(true, expectOk(passphrase(at8, 4, PASSPHRASES.get(3))).json("$.accepted"));
    Response now = view(at8);
    assertEquals("INVESTIGATION", now.json("$.phase"), "the fourth file opens the investigation at once");
    assertEquals(20, (int) now.json("$.suspects.length()"));
    assertEquals(480, count("SELECT all_decrypted_at_seconds FROM team_final WHERE team_id = " + alpha.id()));
    clock.advance(Duration.ofMinutes(12));
    expectOk(suspect(fresh(alpha), CORRECT_SUSPECT));
    admin = adminToken();
    assertEquals(new BigDecimal("15.00"), finalPart("Alpha", "clues"));
    assertEquals(new BigDecimal("10.00"), finalPart("Alpha", "suspect"));
    assertEquals(new BigDecimal("15.00"), finalPart("Alpha", "time"));
    assertEquals(new BigDecimal("40.00"), finalPart("Alpha", "total"));
  }

  @Test
  void aWrongSuspectAtTwentyGetsHalfTheTimePoints() {
    openFinal();
    playTo(alpha, 4);
    clock.advance(Duration.ofMinutes(20));
    expectOk(suspect(fresh(alpha), WRONG_SUSPECT));
    admin = adminToken();
    assertEquals(new BigDecimal("15.00"), finalPart("Alpha", "clues"));
    assertEquals(new BigDecimal("0.00"), finalPart("Alpha", "suspect"));
    assertEquals(new BigDecimal("7.50"), finalPart("Alpha", "time"));
    assertEquals(new BigDecimal("22.50"), finalPart("Alpha", "total"));
  }

  @Test
  void atThirtyMinutesTheTimeIsTenForACorrectSuspectAndFiveForAWrongOne() {
    TestTeam echo = team("Echo", GroupCode.C);
    clues(echo, RoomCode.values());
    openFinal();
    playTo(alpha, 4);
    playTo(echo, 4);
    clock.advance(Duration.ofMinutes(30));
    expectOk(suspect(fresh(alpha), CORRECT_SUSPECT));
    expectOk(suspect(fresh(echo), WRONG_SUSPECT));
    admin = adminToken();
    assertEquals(new BigDecimal("10.00"), finalPart("Alpha", "time"));
    assertEquals(new BigDecimal("35.00"), finalPart("Alpha", "total"));
    assertEquals(new BigDecimal("5.00"), finalPart("Echo", "time"));
    assertEquals(new BigDecimal("20.00"), finalPart("Echo", "total"));
  }

  @Test
  void atTwentyFiveThirtyTheTimeIsFourteenFiftyForACorrectSuspectAndSevenTwentyFiveForAWrongOne() {
    TestTeam echo = team("Echo", GroupCode.C);
    clues(echo, RoomCode.values());
    openFinal();
    playTo(alpha, 4);
    playTo(echo, 4);
    clock.advance(Duration.ofMinutes(25).plusSeconds(30));
    expectOk(suspect(fresh(alpha), CORRECT_SUSPECT));
    expectOk(suspect(fresh(echo), WRONG_SUSPECT));
    admin = adminToken();
    assertEquals(new BigDecimal("14.50"), finalPart("Alpha", "time"));
    assertEquals(new BigDecimal("7.25"), finalPart("Echo", "time"));
    assertEquals(new BigDecimal("39.50"), finalPart("Alpha", "total"));
    assertEquals(new BigDecimal("22.25"), finalPart("Echo", "total"));
  }

  @Test
  void aFourthFileAtThirtyMinutesOpensThePhaseAtOnceAndACorrectSuspectAtThirtyEightScoresTwentySeven() {
    openFinal();
    playTo(alpha, 3);
    clock.advance(Duration.ofMinutes(30));
    TestTeam late = fresh(alpha);
    assertEquals("DECRYPTION", view(late).json("$.phase"), "thirty minutes in, nothing has closed");
    expectOk(passphrase(late, 4, PASSPHRASES.get(3)));
    assertEquals("INVESTIGATION", view(late).json("$.phase"));
    clock.advance(Duration.ofMinutes(8));
    expectOk(suspect(fresh(alpha), CORRECT_SUSPECT));
    admin = adminToken();
    assertEquals(new BigDecimal("2.00"), finalPart("Alpha", "time"));
    assertEquals(new BigDecimal("27.00"), finalPart("Alpha", "total"));
  }

  @Test
  void phaseOneStaysOpenAfterTheInvestigationOpensAndARepeatedFileIsNotCountedTwice() {
    openFinal();
    playTo(alpha, 4);
    assertEquals("INVESTIGATION", view(alpha).json("$.phase"));
    assertEquals(4, count("SELECT count(*) FROM final_clue_attempt WHERE team_id = " + alpha.id() + " AND correct"));
    for (int clue = 1; clue <= 4; clue++) {
      expectError(passphrase(alpha, clue, PASSPHRASES.get(clue - 1)), 409, "This evidence file is already decrypted.");
    }
    assertEquals(4, count("SELECT count(*) FROM final_clue_attempt WHERE team_id = " + alpha.id() + " AND correct"));
    assertEquals(4, count("SELECT count(*) FROM final_clue_attempt WHERE team_id = " + alpha.id()), "a refused repeat is not an attempt");
    Response again = view(fresh(alpha));
    assertEquals("INVESTIGATION", again.json("$.phase"));
    assertEquals(List.of(true, true, true, true), again.json("$.clues[*].solved"));
    assertEquals(4, ((List<?>) again.json("$.clues[?(@.report != null)]")).size(), "the reports stay readable");
    assertEquals(1, count("SELECT count(*) FROM team_final WHERE team_id = " + alpha.id() + " AND all_decrypted_at_seconds IS NOT NULL"));
  }

  @Test
  void aTeamThatRecoveredLateKeepsItsDecryptionPhaseUntilItHasAllFourFiles() {
    openFinal();
    clock.advance(Duration.ofMinutes(30));
    // Bravo passes its last ML stage at 30:00 of active time: S = 1800. Nothing closes at 15 minutes from S.
    TestTeam late = fresh(bravo);
    recordPassedBefore(late, "ML", 6);
    Response finished = expectOk(mlCheck(late, 6, ML_ANSWERS.get(5)));
    assertEquals(true, finished.json("$.recoveryFinished"));
    assertEquals(1800, count("SELECT finished_active_seconds FROM team_recovery WHERE team_id = " + bravo.id()));
    playTo(late, 3);
    clock.advance(Duration.ofMinutes(9));
    late = fresh(late);
    Response v = view(late);
    assertEquals("DECRYPTION", v.json("$.phase"), "39:00: still Phase 1, 9 minutes after S");
    expectError(suspect(late, CORRECT_SUSPECT), 409, DECRYPT_FIRST);
    assertEquals(true, expectOk(passphrase(late, 4, PASSPHRASES.get(3))).json("$.accepted"), "it can still decrypt");
    assertEquals("INVESTIGATION", view(late).json("$.phase"), "and reaches Phase 2 before 40:00");
    clock.advance(Duration.ofSeconds(30));
    expectOk(suspect(fresh(late), CORRECT_SUSPECT));
    admin = adminToken();
    Response board = expectOk(get("/admin/final/board", admin));
    String row = "$[?(@.teamName == 'Bravo')]";
    assertEquals(List.of(1800), board.json(row + ".cryptoFromSeconds"));
    assertEquals(List.of(2340), board.json(row + ".cryptoToSeconds"), "the window ends when the fourth file was decrypted (39:00)");
    assertEquals(new BigDecimal("0.50"), finalPart("Bravo", "time"), "39.5 minutes: 40 - 39.5");
  }

  @Test
  void twoRequestsForTheFourthFileMakeOneTransition() throws Exception {
    openFinal();
    playTo(alpha, 3);
    clock.advance(Duration.ofMinutes(7));
    TestTeam at7 = fresh(alpha);
    List<Callable<Integer>> calls = new ArrayList<>();
    for (int i = 0; i < 6; i++) calls.add(() -> passphrase(at7, 4, PASSPHRASES.get(3)).status());
    List<Integer> statuses = runTogether(calls);
    assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
    assertTrue(statuses.stream().noneMatch(s -> s >= 500), "no 500: " + statuses);
    assertEquals(4, count("SELECT count(*) FROM final_clue_attempt WHERE team_id = " + alpha.id() + " AND correct"));
    assertEquals(420, count("SELECT all_decrypted_at_seconds FROM team_final WHERE team_id = " + alpha.id()), "one transition, at the first acceptance");
    assertEquals("INVESTIGATION", view(fresh(alpha)).json("$.phase"));
  }

  @Test
  void pausedMinutesAreNotCountedInTheSubmissionTime() {
    openFinal();
    playTo(alpha, 4);
    clock.advance(Duration.ofMinutes(20));
    admin = adminToken();
    expectOk(post("/admin/final/timer/pause", admin, null));
    clock.advance(Duration.ofMinutes(5));
    admin = adminToken();
    expectOk(post("/admin/final/timer/resume", admin, null));
    clock.advance(Duration.ofMinutes(10));
    assertEquals("INVESTIGATION", view(fresh(alpha)).json("$.phase"));
    expectOk(suspect(fresh(alpha), CORRECT_SUSPECT));
    assertEquals(1800, count("SELECT active_seconds FROM team_final WHERE team_id = " + alpha.id()), "20 + 10 active minutes: the 5 paused ones are not counted");
    admin = adminToken();
    assertEquals(new BigDecimal("10.00"), finalPart("Alpha", "time"));
  }

  @Test
  void atFortyMinutesEveryActionIsRefusedAndReadingTheBoardStillWorks() {
    TestTeam echo = team("Echo", GroupCode.C);
    clues(echo, RoomCode.values());
    openFinal();
    playTo(alpha, 3);
    playTo(echo, 4);
    clock.advance(Duration.ofMinutes(40));
    TestTeam over = fresh(alpha);
    expectError(passphrase(over, 4, PASSPHRASES.get(3)), 409, "Time is up for the final.");
    expectError(suspect(fresh(echo), CORRECT_SUSPECT), 409, "Time is up for the final.");
    expectError(suspect(over, CORRECT_SUSPECT), 409, "Your final is already over.");
    expectError(startCase(over), 409, "Your final is already over.");
    admin = adminToken();
    Response board = expectOk(get("/admin/final/board", admin));
    assertEquals(2, ((List<?>) board.json("$[?(@.teamName == 'Alpha' || @.teamName == 'Echo')]")).size());
    expectOk(get("/admin/scores", admin));
    expectOk(get("/admin/final/timer", admin));
    assertEquals("COMPLETED", view(fresh(alpha)).json("$.status"));
    assertEquals(new BigDecimal("15.00"), finalPart("Echo", "total"), "four files, time up before a suspect");
  }

  @Test
  void theAdminSeesTheEstimateAndTheCryptoWindowOfEveryTeam() {
    openFinal();
    playTo(alpha, 1);
    admin = adminToken();
    Response timer = expectOk(get("/admin/final/timer", admin));
    assertEquals(15, (int) timer.json("$.phase1Minutes"));
    assertFalse(((Map<?, ?>) timer.json("$")).containsKey("phase1EndsInSeconds"));
    assertFalse(((Map<?, ?>) timer.json("$")).containsKey("finalPhase"));
    String row = "$[?(@.teamName == 'Alpha')]";
    Response before = expectOk(get("/admin/final/board", admin));
    assertEquals(List.of("DECRYPTION"), before.json(row + ".phase"));
    assertEquals(List.of(0), before.json(row + ".cryptoFromSeconds"));
    assertEquals(0, ((List<?>) before.json(row + ".cryptoToSeconds")).stream().filter(java.util.Objects::nonNull).count(), "until the end: no end yet");
    clock.advance(Duration.ofMinutes(15));
    admin = adminToken();
    assertEquals(List.of("DECRYPTION"), expectOk(get("/admin/final/board", admin)).json(row + ".phase"), "15 minutes change nothing");
    TestTeam later = fresh(alpha);
    for (int clue = 2; clue <= 4; clue++) expectOk(passphrase(later, clue, PASSPHRASES.get(clue - 1)));
    admin = adminToken();
    Response after = expectOk(get("/admin/final/board", admin));
    assertEquals(List.of("INVESTIGATION"), after.json(row + ".phase"));
    assertEquals(List.of(900), after.json(row + ".cryptoToSeconds"));
  }

  // ---- the Final Investigation Report -----------------------------------------------------------

  /** The suspect request with a report: {@code report} is the JSON of the extra fields (e.g. "evidence1":"x"). */
  private Response suspectWithReport(TestTeam team, String suspectId, String report) {
    return teamPost(team, "/team/final/suspect", UUID.randomUUID(), "{\"suspectId\":" + quote(suspectId) + (report.isEmpty() ? "" : "," + report) + "}");
  }

  @Test
  void theReportIsStoredOnceWithTheSuspectShownBackToItsTeamOnlyAndNeverLogged() {
    openFinal();
    playTo(alpha, 4);
    openInvestigationPhase();
    String report = "\"evidence1\":\"  Badge log 9:17 PM  \",\"evidence2\":\"S11 left at 9:31, not 9:00\",\"evidence3\":\"\",\"explanation\":\"Line one\\nLine two\"";
    Response submitted = expectOk(suspectWithReport(alpha, CORRECT_SUSPECT, report));
    assertEquals("SUBMITTED", submitted.json("$.screen"));
    assertFalse(submitted.body().contains("Badge log"), "the answer never echoes the report");
    Response done = view(alpha);
    assertEquals("Samer Murad", done.json("$.submission.suspectName"));
    assertEquals("S11", done.json("$.submission.suspectId"));
    assertEquals("Badge log 9:17 PM", done.json("$.submission.report.evidence1"), "trimmed");
    assertEquals("S11 left at 9:31, not 9:00", done.json("$.submission.report.evidence2"));
    assertNull(done.json("$.submission.report.evidence3"), "a blank box is none");
    assertNull(done.json("$.submission.report.evidence4"));
    assertEquals("Line one\nLine two", done.json("$.submission.report.explanation"));
    // The same suspect again is the same answer and the report cannot be changed; another team never sees it.
    expectOk(suspectWithReport(alpha, CORRECT_SUSPECT, "\"evidence1\":\"Changed\""));
    assertEquals("Badge log 9:17 PM", view(alpha).json("$.submission.report.evidence1"));
    assertEquals("Badge log 9:17 PM", db.queryForObject("SELECT evidence1 FROM team_final WHERE team_id = ?", String.class, alpha.id()));
    assertFalse(view(bravo).body().contains("Badge log"), "another team's view never carries it");
    assertEquals(0, count("SELECT count(*) FROM audit_event WHERE action = 'EVENT_FINAL_SUBMITTED' AND (target LIKE '%Badge%' OR target LIKE '%Line one%')"));
  }

  @Test
  void theReportIsRefusedWithTheSuspectInTheDecryptionPhaseAndStoresNothing() {
    openFinal();
    playTo(alpha, 3);
    expectError(suspectWithReport(alpha, CORRECT_SUSPECT, "\"evidence1\":\"too early\""), 409, "Decrypt all four files to open the investigation.");
    assertNull(db.queryForObject("SELECT evidence1 FROM team_final WHERE team_id = ?", String.class, alpha.id()));
    assertNull(db.queryForObject("SELECT suspect_id FROM team_final WHERE team_id = ?", String.class, alpha.id()));
  }

  @Test
  void everyTooLongOrControlCharacterFieldIsListedAndNothingIsStored() {
    openFinal();
    playTo(alpha, 4);
    openInvestigationPhase();
    String longText = "x".repeat(601);
    Response refused = suspectWithReport(
      alpha,
      CORRECT_SUSPECT,
      "\"evidence1\":\"" + longText + "\",\"evidence3\":\"bell\\u0007\",\"explanation\":\"" + longText + "\""
    );
    assertEquals(400, refused.status());
    Map<?, ?> errors = refused.json("$.errors");
    assertEquals(Set.of("evidence1", "evidence3", "explanation"), errors.keySet(), "every field is listed at once");
    assertNull(db.queryForObject("SELECT suspect_id FROM team_final WHERE team_id = ?", String.class, alpha.id()));
    String exactly600 = "y".repeat(600);
    expectOk(suspectWithReport(alpha, CORRECT_SUSPECT, "\"evidence1\":\"" + exactly600 + "\""));
    assertEquals(exactly600, view(alpha).json("$.submission.report.evidence1"));
  }

  @Test
  void theScoreIsTheSameWithAndWithoutAReport() {
    TestTeam echo = team("Echo", GroupCode.C);
    clues(echo, RoomCode.values());
    openFinal();
    playTo(alpha, 4);
    playTo(echo, 4);
    openInvestigationPhase();
    expectOk(suspectWithReport(alpha, CORRECT_SUSPECT, "\"evidence1\":\"Badge log\",\"explanation\":\"Because.\""));
    expectOk(suspectWithReport(echo, CORRECT_SUSPECT, ""));
    for (String part : List.of("clues", "suspect", "time", "total")) {
      assertEquals(finalPart("Echo", part), finalPart("Alpha", part), part);
    }
    assertEquals(new BigDecimal("40.00"), finalPart("Alpha", "total"));
  }

  @Test
  void theOrganizersListAndTheSpreadsheetCarryEveryReportAndNoTeamMemberCanReadThem() {
    TestTeam echo = team("Echo", GroupCode.C);
    clues(echo, RoomCode.values());
    openFinal();
    playTo(alpha, 4);
    playTo(echo, 4);
    openInvestigationPhase();
    expectOk(suspectWithReport(alpha, CORRECT_SUSPECT, "\"evidence1\":\"Badge, \\\"log\\\"\",\"explanation\":\"Line one\\nLine two\""));
    expectOk(suspectWithReport(echo, WRONG_SUSPECT, ""));
    assertEquals(403, get("/admin/final/reports", alpha.token()).status());
    assertEquals(401, get("/admin/final/reports", null).status());
    admin = adminToken();
    Response reports = expectOk(get("/admin/final/reports", admin));
    assertEquals(List.of(true), reports.json("$[?(@.teamName == 'Alpha')].submitted"));
    assertEquals(List.of("Samer Murad"), reports.json("$[?(@.teamName == 'Alpha')].suspectName"));
    assertEquals(List.of(true), reports.json("$[?(@.teamName == 'Alpha')].correct"));
    assertEquals(List.of(false), reports.json("$[?(@.teamName == 'Echo')].correct"));
    assertEquals(List.of("Badge, \"log\""), reports.json("$[?(@.teamName == 'Alpha')].evidence1"));
    assertEquals(List.of(false), reports.json("$[?(@.teamName == 'Bravo')].submitted"), "a team that did not submit is listed too");
    assertNull(reports.<List<String>>json("$[?(@.teamName == 'Bravo')].suspectId").get(0));
    String csv = expectOk(get("/admin/export.csv", admin)).body();
    assertTrue(csv.startsWith("rank,team,group,status,"), csv);
    assertTrue(csv.contains(",evidence1,evidence2,evidence3,evidence4,explanation\r\n"), csv);
    assertTrue(csv.contains("\"Badge, \"\"log\"\"\",\"\",\"\",\"\",\"Line one\nLine two\"\r\n"), "quoted, new line kept inside the quotes: " + csv);
  }

  @Test
  void concurrentSuspectSubmissionsRecordExactlyOne() throws Exception {
    openFinal();
    playTo(alpha, 4);
    openInvestigationPhase();
    List<Callable<Integer>> calls = new ArrayList<>();
    for (String id : List.of("S01", "S02", "S04", "S05", "S08", "S11")) calls.add(() -> suspect(alpha, id).status());
    List<Integer> statuses = runTogether(calls);
    assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
    assertEquals(5, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
    assertEquals(1, count("SELECT count(*) FROM team_final WHERE team_id = " + alpha.id() + " AND suspect_id IS NOT NULL AND finalised_cause = 'SUBMITTED'"));
  }

  @Test
  void concurrentRightPassphrasesSolveAClueOnce() throws Exception {
    openFinal();
    playTo(alpha, 0);
    List<Callable<Integer>> calls = new ArrayList<>();
    for (int i = 0; i < 6; i++) calls.add(() -> passphrase(alpha, 1, PASSPHRASES.get(0)).status());
    List<Integer> statuses = runTogether(calls);
    assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
    assertEquals(5, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
    assertEquals(1, count("SELECT count(*) FROM final_clue_attempt WHERE team_id = " + alpha.id()));
  }

  // ---- score ------------------------------------------------------------------------------------

  @Test
  void aPerfectFinalScoresFortyAndTimePointsFollowTheActiveMinutes() {
    TestTeam echo = team("Echo", GroupCode.C);
    TestTeam foxtrot = team("Foxtrot", GroupCode.D);
    clues(echo, RoomCode.values());
    clues(foxtrot, RoomCode.values());
    openFinal();
    for (TestTeam team : List.of(alpha, echo, foxtrot)) playTo(team, 4);
    clock.advance(Duration.ofMinutes(25));
    expectOk(suspect(fresh(alpha), CORRECT_SUSPECT));
    clock.advance(Duration.ofMinutes(5));
    expectOk(suspect(fresh(echo), CORRECT_SUSPECT));
    clock.advance(Duration.ofMinutes(9));
    expectOk(suspect(fresh(foxtrot), WRONG_SUSPECT));
    admin = adminToken();
    assertEquals(new BigDecimal("40.00"), finalPart("Alpha", "total"));
    assertEquals(new BigDecimal("15.00"), finalPart("Alpha", "time"));
    assertEquals(new BigDecimal("15.00"), finalPart("Alpha", "clues"));
    assertEquals(new BigDecimal("35.00"), finalPart("Echo", "total"), "30 active minutes: 10 time points");
    assertEquals(new BigDecimal("10.00"), finalPart("Echo", "time"));
    assertEquals(new BigDecimal("0.00"), finalPart("Foxtrot", "suspect"));
    assertEquals(new BigDecimal("15.00"), finalPart("Foxtrot", "clues"));
    assertEquals(new BigDecimal("0.50"), finalPart("Foxtrot", "time"), "39 active minutes: 40 - 39 = 1, half of it for a wrong suspect");
    assertEquals(1500, (int) db.queryForObject("SELECT active_seconds FROM team_final WHERE team_id = ?", Integer.class, alpha.id()));
  }

  @Test
  void eachDecryptedFileIsWorthThreePointSevenFiveAndTimePointsNeedAllFourAndASuspect() {
    TestTeam echo = team("Echo", GroupCode.C);
    clues(echo, RoomCode.values());
    openFinal();
    playTo(alpha, 0);
    assertEquals(new BigDecimal("0.00"), finalPart("Alpha", "total"));
    for (int n = 1; n <= 3; n++) {
      expectOk(passphrase(alpha, n, PASSPHRASES.get(n - 1)));
      assertEquals(new BigDecimal("3.75").multiply(BigDecimal.valueOf(n)).setScale(2), finalPart("Alpha", "clues"), n + " files");
      assertEquals(n, (int) expectOk(get("/admin/scores", admin)).<List<Integer>>json("$[?(@.teamName == 'Alpha')].finalScore.cluesSolved").get(0));
    }
    // Three files and the right suspect: no time points.
    playTo(echo, 4);
    openInvestigationPhase();
    expectOk(suspect(alpha, CORRECT_SUSPECT));
    assertEquals(new BigDecimal("11.25"), finalPart("Alpha", "clues"));
    assertEquals(new BigDecimal("10.00"), finalPart("Alpha", "suspect"));
    assertEquals(new BigDecimal("0.00"), finalPart("Alpha", "time"));
    assertEquals(new BigDecimal("21.25"), finalPart("Alpha", "total"));
    // All four files, then time up before a suspect: no suspect and no time points.
    clock.advance(Duration.ofMinutes(41));
    admin = adminToken();
    assertEquals(new BigDecimal("15.00"), finalPart("Echo", "clues"));
    assertEquals(new BigDecimal("0.00"), finalPart("Echo", "time"));
    assertEquals(new BigDecimal("0.00"), finalPart("Echo", "suspect"));
    assertEquals(new BigDecimal("15.00"), finalPart("Echo", "total"));
  }

  @Test
  void pausedMinutesDoNotCountAsActiveTimeAndAddedTimeAppliesToEveryone() {
    TestTeam echo = team("Echo", GroupCode.C);
    clues(echo, RoomCode.values());
    openFinal();
    for (TestTeam team : List.of(alpha, echo)) playTo(team, 4);
    clock.advance(Duration.ofMinutes(5));
    admin = adminToken();
    Response paused = expectOk(post("/admin/final/timer/pause", admin, null));
    assertEquals("PAUSED", paused.json("$.phase"));
    assertEquals(2100, (int) paused.json("$.remainingSeconds"));
    clock.advance(Duration.ofMinutes(10));
    assertEquals(2100, (int) view(fresh(alpha)).json("$.remainingSeconds"));
    assertEquals(2100, (int) view(fresh(echo)).json("$.remainingSeconds"));
    expectError(suspect(fresh(alpha), CORRECT_SUSPECT), 409, "The final is paused.");
    admin = adminToken();
    expectOk(post("/admin/final/timer/resume", admin, null));
    UUID key = UUID.randomUUID();
    Response added = expectOk(call(HttpMethod.POST, "/admin/final/timer/add", admin, "{\"seconds\":300}", key.toString()));
    assertEquals(2400, (int) added.json("$.remainingSeconds"));
    expectOk(call(HttpMethod.POST, "/admin/final/timer/add", admin, "{\"seconds\":300}", key.toString()));
    assertEquals(2400, (int) expectOk(get("/admin/final/timer", admin)).json("$.remainingSeconds"), "a repeated key adds the time once");
    clock.advance(Duration.ofMinutes(20));
    expectOk(suspect(fresh(alpha), CORRECT_SUSPECT));
    admin = adminToken();
    assertEquals(1500, (int) db.queryForObject("SELECT active_seconds FROM team_final WHERE team_id = ?", Integer.class, alpha.id()), "5 + 20 active minutes; the 10 paused ones are not counted");
    assertEquals(new BigDecimal("40.00"), finalPart("Alpha", "total"));
    // Everyone shares the one timer.
    assertEquals((int) view(fresh(alpha)).json("$.remainingSeconds"), (int) view(fresh(echo)).json("$.remainingSeconds"));
    List<String> actions = db.queryForList("SELECT action FROM audit_event WHERE action LIKE 'EVENT_FINAL_%' ORDER BY id", String.class);
    assertTrue(actions.containsAll(List.of("EVENT_FINAL_PAUSED", "EVENT_FINAL_RESUMED", "EVENT_FINAL_TIME_ADDED", "EVENT_FINAL_CLUE_SOLVED")), actions.toString());
    assertEquals(403, post("/admin/final/timer/pause", fresh(alpha).token(), null).status());
    assertEquals(403, post("/admin/final/timer/pause", tokenOfUser(managerIds.get(RoomCode.AI)), null).status());
    expectError(call(HttpMethod.POST, "/admin/final/timer/add", admin, "{\"seconds\":0}", UUID.randomUUID().toString()), 400, "Please check the form fields.");
  }

  // ---- time up ----------------------------------------------------------------------------------

  @Test
  void whenTimeIsUpInputStopsAndTheTeamIsFinalisedExactlyOnce() {
    openFinal();
    playTo(alpha, 3);
    clock.advance(Duration.ofMinutes(41));
    TestTeam later = fresh(alpha);
    Response refused = passphrase(later, 4, PASSPHRASES.get(3));
    expectError(refused, 409, "Time is up for the final.");
    assertEquals(1, count("SELECT count(*) FROM team_final WHERE team_id = " + alpha.id() + " AND finalised_cause = 'TIME_UP'"), "the refusal still finalised the team");
    expectError(passphrase(later, 4, PASSPHRASES.get(3)), 409, "Your final is already over.");
    expectError(suspect(later, CORRECT_SUSPECT), 409, "Your final is already over.");
    Response view = view(later);
    assertEquals("COMPLETED", view.json("$.status"));
    assertEquals("SUBMITTED", view.json("$.screen"));
    assertEquals(false, view.json("$.submitted"));
    assertEquals("Time is up. The final is over for your team.", view.json("$.submission.message"));
    assertEquals(3, count("SELECT count(*) FROM final_clue_attempt WHERE team_id = " + alpha.id() + " AND correct"));
    assertEquals(1, count("SELECT count(*) FROM team_final WHERE team_id = " + alpha.id()));
    assertEquals(0, (int) view(fresh(bravo)).json("$.remainingSeconds"));
    admin = adminToken();
    expectError(
      call(HttpMethod.POST, "/admin/final/timer/add", admin, "{\"seconds\":300}", UUID.randomUUID().toString()),
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
    for (TestTeam racer : current) calls.add(() -> suspect(racer, CORRECT_SUSPECT).status());
    String adminNow = admin;
    calls.add(() -> post("/admin/final/close", adminNow, null).status());
    runTogether(calls);
    for (TestTeam racer : racers) {
      assertEquals(1, count("SELECT count(*) FROM team_final WHERE team_id = " + racer.id()), racer.name());
      assertEquals("TIME_UP", db.queryForObject("SELECT finalised_cause FROM team_final WHERE team_id = ?", String.class, racer.id()), racer.name());
      assertNull(db.queryForObject("SELECT suspect_id FROM team_final WHERE team_id = ?", String.class, racer.id()), "no answer after the time is up: " + racer.name());
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
    for (TestTeam racer : racers) calls.add(() -> suspect(racer, CORRECT_SUSPECT).status());
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
  void closingTheFinalFinalisesEveryTeamOnceAndItCannotReopen() {
    TestTeam charlie = team("Charlie", GroupCode.C);
    clues(charlie, RoomCode.AI);
    openFinal();
    playTo(alpha, 2);
    expectOk(post("/admin/final/close", admin, null));
    assertEquals("COMPLETED", view(alpha).json("$.status"));
    assertEquals("COMPLETED", view(bravo).json("$.status"));
    assertEquals("COMPLETED", view(charlie).json("$.status"), "every team plays, so every team is finalised");
    assertEquals(3, count("SELECT count(*) FROM team_final"));
    assertEquals(3, count("SELECT count(*) FROM team_final WHERE finalised_cause = 'CLOSED'"));
    expectError(post("/admin/final/close", admin, null), 409, "The final is not open.");
    expectError(post("/admin/final/open", admin, null), 409, "The final was already opened once.");
    expectError(passphrase(alpha, 3, PASSPHRASES.get(2)), 409, "The final is not open.");
    expectError(mlCheck(bravo, 1, ML_ANSWERS.get(0)), 409, "The final is not open.");
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

    String broken = good
      .replace("\"correctSuspect\":\"S11\"", "\"correctSuspect\":\"S99\"")
      .replace("\"key\":\"A\"", "\"key\":\"B\"")
      .replace("\"format\":2", "\"format\":1");
    assertFalse(broken.equals(good));
    Response refused = importFinalContent(broken);
    assertEquals(400, refused.status(), refused.body());
    List<String> problems = refused.json("$.problems");
    assertTrue(problems.contains("format: must be 2."), problems.toString());
    assertTrue(problems.contains("correctSuspect: must be the id of one of the suspects."), problems.toString());
    assertTrue(problems.contains("keyIndex: key A must be used exactly once (used 0 times)."), problems.toString());
    assertTrue(problems.contains("keyIndex: key B must be used exactly once (used 2 times)."), problems.toString());
    assertEquals("The file has " + problems.size() + " problems.", refused.detail());
    assertEquals(400, importFinalContent("{{{").status());
    assertEquals(400, importFinalContent("").status());
    assertEquals(400, importFinalContent(good.replace("\"format\":2,", "\"format\":2,\"extra\":1,")).status(), "unknown fields are refused");
    assertEquals(400, importFinalContent(good.replace("\"format\":2,", "\"format\":2,\"layers\":[],")).status(), "the old format's fields are refused");
    assertEquals(stored, db.queryForObject("SELECT content::text FROM final_content", String.class), "a refused import changes nothing");

    Response imported = expectOk(importFinalContent(good));
    assertEquals(25, (int) imported.json("$.fullUntilMinutes"));

    openFinal();
    expectError(importFinalContent(good), 409, "Event content is read-only after the final opens.");
    assertEquals(true, expectOk(get("/admin/final/content", admin)).json("$.readOnly"));
  }

  @Test
  void aMemberNeverReceivesHashesUnsolvedReportsTheCorrectSuspectOrScoresAtAnyStep() {
    TestTeam echo = team("Echo", GroupCode.C);
    clues(echo, RoomCode.values());
    String content = expectOk(get("/admin/final/content", admin)).json("$.content");
    List<String> hashes = Pattern.compile("[0-9a-f]{64}").matcher(content).results().map(MatchResult::group).toList();
    assertEquals(4, hashes.size());
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
    for (int stage = 1; stage <= 6; stage++) {
      bodies.add(expectOk(recoveryView(bravo)).body());
      bodies.add(expectOk(mlCheck(bravo, stage, ML_WRONG.get(stage - 1))).body());
      clock.advance(Duration.ofSeconds(2));
      bodies.add(expectOk(mlCheck(bravo, stage, ML_ANSWERS.get(stage - 1))).body());
      clock.advance(Duration.ofSeconds(2));
    }
    bodies.add(expectOk(recoveryView(bravo)).body());

    bodies.add(expectOk(unlock(alpha, "a", "b", "c", "d")).body());
    bodies.add(expectOk(unlockWithKeys(alpha)).body());
    snapshot.run();
    bodies.add(expectOk(startCase(alpha)).body());
    playTo(echo, 0);
    expectOk(passphrase(echo, 4, PASSPHRASES.get(3)));
    snapshot.run();
    // Alpha never sees a report it did not solve, even one another team (Echo) solved.
    for (int n = 1; n <= 4; n++) {
      bodies.add(expectOk(passphrase(alpha, n, "wrong")).body());
      String alphaView = view(alpha).body();
      for (int later = n; later <= 4; later++) assertFalse(alphaView.contains(REPORT_MARKS.get(later - 1)), "report " + later);
      bodies.add(expectOk(passphrase(alpha, n, PASSPHRASES.get(n - 1))).body());
      snapshot.run();
    }
    openInvestigationPhase();
    bodies.add(expectOk(suspect(alpha, CORRECT_SUSPECT)).body());
    snapshot.run();
    expectOk(put("/admin/publication", admin, "{\"published\":true}"));
    snapshot.run();
    for (String body : bodies) {
      assertFalse(FORBIDDEN_FIELDS.matcher(body).find(), "a private field leaked: " + body);
      for (String hash : hashes) assertFalse(body.contains(hash), "a passphrase hash leaked: " + body);
      for (String secret : PASSPHRASES) assertFalse(body.contains(secret.strip()), "a passphrase leaked: " + body);
      assertFalse(body.contains("split\",\"fit_scaler") || body.contains("authorized==false&&doorOpen"), "a recovery answer leaked: " + body);
    }
    // The published ranking shows scores and nothing else about how they were made.
    String published = expectOk(get("/results", alpha.token())).body();
    assertFalse(published.contains("deduction") || published.contains("cheating") || published.contains("hints"), published);
    assertFalse(published.contains("suspectCorrect") || published.contains("cluesSolved"), published);
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
    reads.put("team recovery", () -> get("/team/final/recovery", bravo.token()));
    reads.put("team final (recovery)", () -> get("/team/final", bravo.token()));
    reads.put("admin recovery", () -> get("/admin/final/recovery", admin));
    reads.put("admin board", () -> get("/admin/board", admin));
    reads.put("admin scores", () -> get("/admin/scores", admin));
    reads.put("admin final board", () -> get("/admin/final/board", admin));
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
