package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PRE-RELEASE VERIFICATION of the final "The Last Shipment" (added for the release check; it only reads the shipped
 * content and drives the public API with a manual clock, it changes no production code).
 *
 * <p>The rules it pins down, as implemented:
 * <ul>
 *   <li>Every team plays. Keys held when the final opens (K): 4 = no recovery; 3 = ML; 0..2 = ML then Arduino.</li>
 *   <li>A team's phase is {@code FinalPhase.of(all_decrypted_at_seconds)}: DECRYPTION until its fourth passphrase is
 *       accepted, then INVESTIGATION. No clock decides it; phase1Minutes (15) is an estimate for the screen only.</li>
 *   <li>A suspect needs INVESTIGATION (409 "Decrypt all four files to open the investigation."). The terminal package
 *       and Phase 1 stay available until the final ends at 40:00.</li>
 *   <li>Final score 40 = 3.75 x files + 10 (correct suspect) + time (15 up to 25:00, then 40 - t; HALF for a wrong
 *       suspect; 0 without four files or without a suspect).</li>
 * </ul>
 */
@Import(ManualClock.Config.class)
class FinalPreReleaseTest extends EventIntegrationTest {

  private static final String NOT_OPEN = "The final is not open.";
  private static final String TIME_UP = "Time is up for the final.";
  private static final String OVER = "Your final is already over.";
  private static final String DECRYPT_FIRST = "Decrypt all four files to open the investigation.";
  private static final String ALREADY_DECRYPTED = "This evidence file is already decrypted.";
  private static final String ALREADY_RECORDED = "Your final answer is already recorded.";
  private static final String FINAL_BUNDLE = "/admin/final/terminal-bundle";
  private static final String TEAM_BUNDLE = "/team/final/terminal-bundle";
  /** Field names that must never appear in a participant response (answers, correctness, scores, solutions). */
  private static final Pattern FORBIDDEN_FIELDS = Pattern.compile(
    "\"(passphraseSha256|passphrase|correctSuspect|suspectCorrect|acceptedAnswers|answer|correct|correctOption|bugs|why|requirement|score|scores|total|rank|finalScore|roomScores|cluePoints|caseKeys|acceptedVariants)\"\\s*:"
  );

  @Autowired
  private ManualClock clock;

  private int groupCounter;

  @BeforeEach
  void prepare() {
    clock.reset();
    groupCounter = 0;
    loadContent();
  }

  // ====================================================================================================
  // Paths
  // ====================================================================================================

  @Test
  void path1_fourKeys_unlock_crypto_fourFilesAt0800_suspectAt2000_scoresForty() {
    TestTeam alpha = teamWithKeys("Alpha", 4);
    assertEquals("LOCKED", view(alpha).json("$.status"), "before the final opens");
    openFinal();
    Response start = view(alpha);
    assertEquals("IN_FINAL", start.json("$.status"));
    assertEquals("UNLOCK", start.json("$.screen"));
    assertNull(start.json("$.recovery"), "four keys need no recovery");
    expectOk(unlockWithKeys(alpha));
    assertEquals("MISSION", view(alpha).json("$.screen"));
    expectOk(startCase(alpha));
    Response crypto = view(alpha);
    assertEquals("CASE", crypto.json("$.screen"));
    assertEquals("DECRYPTION", crypto.json("$.phase"));
    assertEquals(15, (int) crypto.json("$.phase1Minutes"), "the estimate");
    assertEquals(0, (int) crypto.json("$.suspects.length()"));
    clock.advance(Duration.ofMinutes(8));
    TestTeam at8 = fresh(alpha);
    for (int clue : new int[] { 3, 1, 4 }) expectOk(passphrase(at8, clue, PASSPHRASES.get(clue - 1)));
    assertEquals("DECRYPTION", view(at8).json("$.phase"), "three files: still Phase 1");
    assertEquals(true, expectOk(passphrase(at8, 2, PASSPHRASES.get(1))).json("$.accepted"));
    Response investigating = view(at8);
    assertEquals("INVESTIGATION", investigating.json("$.phase"));
    assertEquals(20, (int) investigating.json("$.suspects.length()"));
    assertEquals(480, count("SELECT all_decrypted_at_seconds FROM team_final WHERE team_id = " + alpha.id()));
    clock.advance(Duration.ofMinutes(12));
    Response sent = expectOk(suspect(fresh(alpha), CORRECT_SUSPECT));
    assertEquals("SUBMITTED", sent.json("$.screen"));
    assertScore("Alpha", "15.00", "10.00", "15.00", "40.00");
    assertEquals(1200, count("SELECT active_seconds FROM team_final WHERE team_id = " + alpha.id()));
    assertEquals("COMPLETED", view(fresh(alpha)).json("$.status"));
  }

  @Test
  void path2_threeKeys_mlRecovery_crypto_fourFiles_suspectAt2200_scoresForty() {
    TestTeam bravo = teamWithKeys("Bravo", 3);
    openFinal();
    Response v = view(bravo);
    assertEquals("RECOVERY", v.json("$.status"));
    assertEquals(List.of("ML"), v.json("$.recovery.levels"));
    expectError(unlockWithKeys(bravo), 409, "Finish your recovery levels first.");
    expectError(passphrase(bravo, 1, PASSPHRASES.get(0)), 409, "Finish your recovery levels first.");
    playMlThroughTheApi(bravo);
    Response after = view(bravo);
    assertEquals("IN_FINAL", after.json("$.status"));
    assertEquals("UNLOCK", after.json("$.screen"));
    assertEquals(1, (int) after.json("$.unlock.recoveredKeys.length()"), "the one missing key is shown to this team");
    assertEquals(4, count("SELECT count(*) FROM collected_clue WHERE team_id = " + bravo.id()));
    expectOk(unlockWithKeys(bravo));
    expectOk(startCase(bravo));
    for (int clue = 1; clue <= 4; clue++) expectOk(passphrase(bravo, clue, PASSPHRASES.get(clue - 1)));
    assertEquals("INVESTIGATION", view(bravo).json("$.phase"));
    clock.advance(Duration.ofMinutes(22));
    expectOk(suspect(fresh(bravo), CORRECT_SUSPECT));
    assertScore("Bravo", "15.00", "10.00", "15.00", "40.00");
    Response board = adminBoard();
    String row = "$[?(@.teamName == 'Bravo')]";
    assertEquals(List.of("ML"), board.json(row + ".recoveryPath"));
    assertEquals(List.of(3), board.json(row + ".keysHeld"));
    assertEquals(List.of(6), board.json(row + ".recoveryChecks"));
  }

  @Test
  void path3_twoKeys_ml_then_arduino_crypto_fourFiles_suspect() {
    TestTeam charlie = teamWithKeys("Charlie", 2);
    openFinal();
    assertEquals(List.of("ML", "ARDUINO"), view(charlie).json("$.recovery.levels"));
    playMlThroughTheApi(charlie);
    Response between = view(charlie);
    assertEquals("RECOVERY", between.json("$.status"), "the Arduino level is still to do");
    expectError(unlockWithKeys(charlie), 409, "Finish your recovery levels first.");
    // The wrong option first: not passed, not finished; then the right one.
    String right = rightLetter(charlie);
    String wrong = List.of("A", "B", "C", "D").stream().filter(l -> !l.equals(right)).findFirst().orElseThrow();
    clock.advance(Duration.ofSeconds(3));
    Response failed = expectOk(arduinoRun(charlie, UUID.randomUUID(), arduinoBody(fixedArduinoProgram(), wrong)));
    assertEquals(false, failed.json("$.passed"));
    assertEquals(false, failed.json("$.recoveryFinished"));
    clock.advance(Duration.ofSeconds(3));
    Response passed = expectOk(arduinoRun(charlie, UUID.randomUUID(), arduinoBody(fixedArduinoProgram(), right)));
    assertEquals(true, passed.json("$.passed"));
    assertEquals(true, passed.json("$.recoveryFinished"));
    assertEquals("RCV-7314", passed.json("$.token"), "the cosmetic token of the Arduino level");
    assertEquals(4, count("SELECT count(*) FROM collected_clue WHERE team_id = " + charlie.id()));
    assertEquals(2, (int) view(charlie).json("$.unlock.recoveredKeys.length()"));
    expectOk(unlockWithKeys(charlie));
    expectOk(startCase(charlie));
    for (int clue = 1; clue <= 4; clue++) expectOk(passphrase(charlie, clue, PASSPHRASES.get(clue - 1)));
    clock.advance(Duration.ofMinutes(10));
    expectOk(suspect(fresh(charlie), CORRECT_SUSPECT));
    assertScore("Charlie", "15.00", "10.00", "15.00", "40.00");
    assertEquals(List.of("ML_ARDUINO"), adminBoard().json("$[?(@.teamName == 'Charlie')].recoveryPath"));
  }

  @Test
  void path4_zeroKeys_isAllowed_sameTwoLevels_noCrash_and_everyKeyIsGranted() {
    TestTeam delta = teamWithKeys("Delta", 0);
    openFinal();
    Response v = view(delta);
    assertEquals("RECOVERY", v.json("$.status"));
    assertEquals(List.of("ML", "ARDUINO"), v.json("$.recovery.levels"));
    assertEquals(0, count("SELECT keys_at_open FROM team_recovery WHERE team_id = " + delta.id()));
    finishRecovery(delta);
    assertEquals(4, count("SELECT count(*) FROM collected_clue WHERE team_id = " + delta.id() + " AND source = 'RECOVERY'"));
    assertEquals(4, (int) view(delta).json("$.unlock.recoveredKeys.length()"));
    expectOk(unlockWithKeys(delta));
    expectOk(startCase(delta));
    for (int clue = 1; clue <= 4; clue++) expectOk(passphrase(delta, clue, PASSPHRASES.get(clue - 1)));
    expectOk(suspect(delta, CORRECT_SUSPECT));
    assertEquals("COMPLETED", view(fresh(delta)).json("$.status"));
    assertEquals(List.of(0), adminBoard().json("$[?(@.teamName == 'Delta')].keysHeld"));
  }

  // ====================================================================================================
  // Phase
  // ====================================================================================================

  @Test
  void phase5_threeFilesStayInPhaseOneAt1500_2500_3959_clueSubmissionStillWorks_suspectRefused_closedAt4000_total1125() {
    TestTeam alpha = teamWithKeys("Alpha", 4);
    openFinal();
    playTo(alpha, 3);
    int wrongTries = 0;
    for (Duration step : List.of(Duration.ofMinutes(15), Duration.ofMinutes(10), Duration.ofMinutes(14).plusSeconds(59))) {
      clock.advance(step); // 15:00, 25:00, 39:59 of the final's time
      TestTeam later = fresh(alpha);
      Response v = view(later);
      assertEquals("DECRYPTION", v.json("$.phase"));
      assertEquals(0, (int) v.json("$.suspects.length()"), "no suspects in Phase 1");
      assertEquals(false, expectOk(passphrase(later, 4, "wrong try " + (++wrongTries))).json("$.accepted"), "clue submission is still accepted (and checked)");
      expectError(suspect(later, CORRECT_SUSPECT), 409, DECRYPT_FIRST);
    }
    clock.advance(Duration.ofSeconds(1)); // 40:00
    TestTeam over = fresh(alpha);
    expectError(passphrase(over, 4, PASSPHRASES.get(3)), 409, TIME_UP);
    expectError(suspect(over, CORRECT_SUSPECT), 409, OVER);
    assertEquals("COMPLETED", view(over).json("$.status"));
    assertScore("Alpha", "11.25", "0.00", "0.00", "11.25");
  }

  @Test
  void phase6_fourthFileAt3000_opensTheInvestigationAtOnce_suspectAt3800_time200_total27() {
    TestTeam alpha = teamWithKeys("Alpha", 4);
    openFinal();
    playTo(alpha, 3);
    clock.advance(Duration.ofMinutes(30));
    TestTeam late = fresh(alpha);
    assertEquals("DECRYPTION", view(late).json("$.phase"), "30 minutes in, nothing has closed");
    expectOk(passphrase(late, 4, PASSPHRASES.get(3)));
    assertEquals("INVESTIGATION", view(late).json("$.phase"));
    clock.advance(Duration.ofMinutes(8));
    expectOk(suspect(fresh(alpha), CORRECT_SUSPECT));
    assertScore("Alpha", "15.00", "10.00", "2.00", "27.00");
  }

  @Test
  void phase7_afterTheInvestigationOpens_theTerminalIsStillServed_andARepeatedFileIsNotCountedTwice() {
    expectOk(uploadFinalPackage(admin, shippedFinalPackage()));
    TestTeam alpha = teamWithKeys("Alpha", 4);
    openFinal();
    playTo(alpha, 3);
    assertEquals(200, download(TEAM_BUNDLE, alpha.token()), "Phase 1: served");
    expectOk(passphrase(alpha, 4, PASSPHRASES.get(3)));
    assertEquals("INVESTIGATION", view(alpha).json("$.phase"));
    assertEquals(200, download(TEAM_BUNDLE, alpha.token()), "Phase 2: Phase 1 stays available, the terminal is still served");
    assertNotNull(view(alpha).json("$.terminal"));
    for (int clue = 1; clue <= 4; clue++) expectError(passphrase(alpha, clue, PASSPHRASES.get(clue - 1)), 409, ALREADY_DECRYPTED);
    expectError(passphrase(alpha, 2, "anything else"), 409, ALREADY_DECRYPTED);
    assertEquals(4, count("SELECT count(*) FROM final_clue_attempt WHERE team_id = " + alpha.id() + " AND correct"));
    assertEquals(4, count("SELECT count(*) FROM final_clue_attempt WHERE team_id = " + alpha.id()), "a refused repeat is not an attempt");
    assertEquals(List.of(true, true, true, true), view(alpha).json("$.clues[*].solved"));
    clock.advance(Duration.ofMinutes(40));
    TestTeam over = fresh(alpha);
    expectError(suspect(over, CORRECT_SUSPECT), 409, TIME_UP);
    assertEquals(404, download(TEAM_BUNDLE, fresh(alpha).token()), "after the end the team's final is over: no terminal");
  }

  @Test
  void phase8_10_theSameFourthFileSentSixTimesAtOnce_oneTransition_countFour_no5xx_everySessionSeesInvestigation() throws Exception {
    TestTeam alpha = teamWithKeys("Alpha", 4);
    openFinal();
    playTo(alpha, 3);
    clock.advance(Duration.ofMinutes(7));
    TestTeam at7 = fresh(alpha);
    List<Callable<Response>> calls = new ArrayList<>();
    for (int i = 0; i < 6; i++) calls.add(() -> passphrase(at7, 4, PASSPHRASES.get(3)));
    List<Response> results = runTogether(calls);
    List<Integer> statuses = results.stream().map(Response::status).toList();
    assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
    assertTrue(statuses.stream().noneMatch(s -> s >= 500), "no 5xx: " + statuses);
    assertEquals(5, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
    assertEquals(4, count("SELECT count(*) FROM final_clue_attempt WHERE team_id = " + alpha.id() + " AND correct"));
    assertEquals(420, count("SELECT all_decrypted_at_seconds FROM team_final WHERE team_id = " + alpha.id()), "one transition, at the first acceptance");
    // The team has one login at a time: every new session (another device, a refresh) reads the same stored phase.
    for (int session = 0; session < 3; session++) {
      Response v = view(fresh(alpha));
      assertEquals("INVESTIGATION", v.json("$.phase"), "session " + session);
      assertEquals(20, (int) v.json("$.suspects.length()"));
    }
  }

  @Test
  void phase9_twoDifferentSuspectsAtOnce_exactlyOneIsRecorded_theOtherGetsTheExistingConflict() throws Exception {
    TestTeam alpha = teamWithKeys("Alpha", 4);
    openFinal();
    playTo(alpha, 4);
    clock.advance(Duration.ofMinutes(12));
    TestTeam at12 = fresh(alpha);
    List<Callable<Response>> calls = List.of(() -> suspect(at12, CORRECT_SUSPECT), () -> suspect(at12, WRONG_SUSPECT));
    List<Response> results = runTogether(calls);
    List<Integer> statuses = results.stream().map(Response::status).sorted().toList();
    assertEquals(List.of(200, 409), statuses, results.toString());
    Response refused = results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
    assertEquals(ALREADY_RECORDED, refused.detail());
    assertEquals(1, count("SELECT count(*) FROM team_final WHERE team_id = " + alpha.id() + " AND suspect_id IS NOT NULL"));
    String stored = db.queryForObject("SELECT suspect_id FROM team_final WHERE team_id = ?", String.class, alpha.id());
    assertTrue(List.of(CORRECT_SUSPECT, WRONG_SUSPECT).contains(stored));
    // The score follows whichever was stored (correct: 10 + 15, wrong: 0 + 7.50), never a mix.
    String suspectPoints = stored.equals(CORRECT_SUSPECT) ? "10.00" : "0.00";
    String timePoints = stored.equals(CORRECT_SUSPECT) ? "15.00" : "7.50";
    assertScore("Alpha", "15.00", suspectPoints, timePoints, stored.equals(CORRECT_SUSPECT) ? "40.00" : "22.50");
    // The same choice sent again is the same answer; the other one stays refused.
    expectOk(suspect(fresh(alpha), stored));
    expectError(suspect(fresh(alpha), stored.equals(CORRECT_SUSPECT) ? WRONG_SUSPECT : CORRECT_SUSPECT), 409, ALREADY_RECORDED);
  }

  // ====================================================================================================
  // Recovery
  // ====================================================================================================

  @Test
  void recovery11_finishedAt1000_nothingClosesAt2500_theTeamCanDecryptUntil4000() {
    TestTeam bravo = teamWithKeys("Bravo", 3);
    openFinal();
    clock.advance(Duration.ofMinutes(10));
    TestTeam at10 = fresh(bravo);
    finishRecovery(at10);
    assertEquals(600, count("SELECT finished_active_seconds FROM team_recovery WHERE team_id = " + bravo.id()));
    playTo(at10, 2);
    clock.advance(Duration.ofMinutes(15)); // 25:00
    TestTeam at25 = fresh(bravo);
    assertEquals("DECRYPTION", view(at25).json("$.phase"), "25:00: still Phase 1 (15 minutes after S would have been the old limit)");
    expectOk(passphrase(at25, 3, PASSPHRASES.get(2)));
    clock.advance(Duration.ofMinutes(14)); // 39:00
    TestTeam at39 = fresh(bravo);
    assertEquals("DECRYPTION", view(at39).json("$.phase"));
    expectOk(passphrase(at39, 4, PASSPHRASES.get(3)));
    assertEquals("INVESTIGATION", view(at39).json("$.phase"));
    clock.advance(Duration.ofSeconds(30));
    expectOk(suspect(fresh(bravo), CORRECT_SUSPECT));
    assertScore("Bravo", "15.00", "10.00", "0.50", "25.50");
    Response board = adminBoard();
    String row = "$[?(@.teamName == 'Bravo')]";
    assertEquals(List.of(600), board.json(row + ".cryptoFromSeconds"));
    assertEquals(List.of(2340), board.json(row + ".cryptoToSeconds"), "the window ends at the fourth file (39:00)");
  }

  @Test
  void recovery12_finishedAt3000_fourFilesAt3600_suspectAt3800_scores27_andOnlyThreeFilesCannotSubmit_scores1125() {
    TestTeam bravo = teamWithKeys("Bravo", 3);
    TestTeam echo = teamWithKeys("Echo", 3);
    openFinal();
    clock.advance(Duration.ofMinutes(30));
    TestTeam b30 = fresh(bravo);
    TestTeam e30 = fresh(echo);
    finishRecovery(b30);
    finishRecovery(e30);
    playTo(b30, 3);
    playTo(e30, 3);
    clock.advance(Duration.ofMinutes(6)); // 36:00
    TestTeam b36 = fresh(bravo);
    expectOk(passphrase(b36, 4, PASSPHRASES.get(3)));
    assertEquals("INVESTIGATION", view(b36).json("$.phase"));
    clock.advance(Duration.ofMinutes(2)); // 38:00
    expectError(suspect(fresh(echo), CORRECT_SUSPECT), 409, DECRYPT_FIRST);
    expectOk(suspect(fresh(bravo), CORRECT_SUSPECT));
    clock.advance(Duration.ofMinutes(2)); // 40:00
    expectError(suspect(fresh(echo), CORRECT_SUSPECT), 409, TIME_UP);
    assertScore("Bravo", "15.00", "10.00", "2.00", "27.00");
    assertScore("Echo", "11.25", "0.00", "0.00", "11.25");
  }

  @Test
  void recovery13_recoveryNeverFinished_at4000_everythingIsZero_andReadingTheBoardDoesNotThrow() {
    TestTeam bravo = teamWithKeys("Bravo", 3);
    TestTeam delta = teamWithKeys("Delta", 0);
    openFinal();
    expectOk(mlCheck(bravo, 1, ML_ANSWERS.get(0)));
    clock.advance(Duration.ofMinutes(40));
    expectError(mlCheck(fresh(bravo), 2, ML_ANSWERS.get(1)), 409, TIME_UP);
    expectError(unlockWithKeys(fresh(delta)), 409, TIME_UP);
    assertScore("Bravo", "0.00", "0.00", "0.00", "0.00");
    assertScore("Delta", "0.00", "0.00", "0.00", "0.00");
    Response board = adminBoard();
    assertEquals(List.of("Time up"), board.json("$[?(@.teamName == 'Bravo')].stage"));
    expectOk(get("/admin/scoreboard", adminToken()));
    expectOk(get("/admin/final/recovery", adminToken()));
    expectOk(get("/admin/final/reports", adminToken()));
    assertEquals("COMPLETED", view(fresh(bravo)).json("$.status"));
    assertEquals(3, count("SELECT count(*) FROM collected_clue WHERE team_id = " + bravo.id()), "no key is granted by an unfinished recovery");
  }

  @Test
  void recovery14_wrongAnswersAreRejectedAndCounted_theEvidenceWordIsCaseAndSpaceInsensitive_theTokensAreOutputsNotInputs() {
    TestTeam bravo = teamWithKeys("Bravo", 3);
    openFinal();
    recordPassedBefore(bravo, "ML", 6);
    for (int tries = 0; tries < 4; tries++) {
      clock.advance(Duration.ofSeconds(2));
      Response wrong = expectOk(mlCheck(fresh(bravo), 6, tries == 0 ? ML_WRONG.get(5) : tries == 1 ? "{\"word\":\"CLU\"}" : tries == 2 ? "{\"word\":\"ML-8842\"}" : "{\"word\":\"RCV-7314\"}"));
      assertEquals(false, wrong.json("$.passed"), "try " + tries);
      assertNull(wrong.json("$.token"), "a failed check never shows the token");
    }
    assertEquals(4, count("SELECT count(*) FROM recovery_check WHERE team_id = " + bravo.id() + " AND stage = 6 AND NOT passed"));
    assertEquals(List.of(4 + 5), adminBoard().json("$[?(@.teamName == 'Bravo')].recoveryChecks"), "5 recorded passes + 4 wrong tries");
    clock.advance(Duration.ofSeconds(2));
    Response right = expectOk(mlCheck(fresh(bravo), 6, "{\"word\":\"  ClUe \"}"));
    assertEquals(true, right.json("$.passed"), "case and surrounding spaces do not matter");
    assertEquals("ML-8842", right.json("$.token"));
    assertEquals(true, right.json("$.recoveryFinished"));
    clock.advance(Duration.ofSeconds(2));
    expectError(mlCheck(fresh(bravo), 6, ML_ANSWERS.get(5)), 409, "Your recovery is already complete.");
    // A later stage cannot be played before the earlier ones.
    TestTeam charlie = teamWithKeys("Charlie", 3);
    expectError(mlCheck(charlie, 3, ML_ANSWERS.get(2)), 409, "Finish the earlier recovery stages first.");
  }

  // ====================================================================================================
  // Scoring
  // ====================================================================================================

  @Test
  void scoring15_16_17_theTimeTable_correctAndWrong_andAFileFullTeamThatNeverSubmitsKeepsFifteen() {
    record Case(String name, boolean correct, Duration at, String time, String total) {}
    List<Case> cases = List.of(
      new Case("W2000", false, Duration.ofMinutes(20), "7.50", "22.50"),
      new Case("C2500", true, Duration.ofMinutes(25), "15.00", "40.00"),
      new Case("C2530", true, Duration.ofMinutes(25).plusSeconds(30), "14.50", "39.50"),
      new Case("W2530", false, Duration.ofMinutes(25).plusSeconds(30), "7.25", "22.25"),
      new Case("C3000", true, Duration.ofMinutes(30), "10.00", "35.00"),
      new Case("W3000", false, Duration.ofMinutes(30), "5.00", "20.00"),
      new Case("C3330", true, Duration.ofMinutes(33).plusSeconds(30), "6.50", "31.50"),
      new Case("W3959", false, Duration.ofMinutes(39).plusSeconds(59), "0.01", "15.01")
    );
    List<TestTeam> teams = new ArrayList<>();
    for (Case c : cases) teams.add(teamWithKeys(c.name(), 4));
    TestTeam never = teamWithKeys("NEVER", 4);
    openFinal();
    for (TestTeam team : teams) playTo(team, 4);
    playTo(never, 4);
    Duration elapsed = Duration.ZERO;
    for (int i = 0; i < cases.size(); i++) {
      clock.advance(cases.get(i).at().minus(elapsed));
      elapsed = cases.get(i).at();
      expectOk(suspect(fresh(teams.get(i)), cases.get(i).correct() ? CORRECT_SUSPECT : WRONG_SUSPECT));
    }
    clock.advance(Duration.ofSeconds(1)); // 40:00: the final ends
    for (Case c : cases) {
      String clues = "15.00";
      String suspect = c.correct() ? "10.00" : "0.00";
      assertScore(c.name(), clues, suspect, c.time(), c.total());
    }
    assertScore("NEVER", "15.00", "0.00", "0.00", "15.00");
    // The admin board and the scoreboard agree with the breakdown, to the cent, for every team.
    String adminTokenNow = adminToken();
    Response board = expectOk(get("/admin/final/board", adminTokenNow));
    Response scores = expectOk(get("/admin/scores", adminTokenNow));
    for (Case c : cases) {
      String row = "$[?(@.teamName == '" + c.name() + "')]";
      assertEquals(new BigDecimal(c.total()), new BigDecimal(String.valueOf(((List<?>) board.json(row + ".finalScore")).getFirst())).setScale(2));
      assertEquals(new BigDecimal(c.total()), new BigDecimal(String.valueOf(((List<?>) scores.json(row + ".total")).getFirst())).setScale(2), "no room points: the team total is the final's");
    }
  }

  @Test
  void scoring18_aReportIsStoredForTheAdminOnly_neverChangesAScore_andLongOrEmptyReportsNeverGiveA5xx() {
    TestTeam withReport = teamWithKeys("WithReport", 4);
    TestTeam withoutReport = teamWithKeys("WithoutReport", 4);
    TestTeam tooLong = teamWithKeys("TooLong", 4);
    openFinal();
    for (TestTeam team : List.of(withReport, withoutReport, tooLong)) playTo(team, 4);
    clock.advance(Duration.ofMinutes(18));
    Response refused = suspectWithReport(fresh(tooLong), CORRECT_SUSPECT, "\"evidence1\":\"" + "x".repeat(601) + "\",\"explanation\":\"" + "y".repeat(5000) + "\"");
    assertEquals(400, refused.status(), "too long: a clear 400, never a 5xx");
    assertEquals(0, count("SELECT count(*) FROM team_final WHERE team_id = " + tooLong.id() + " AND suspect_id IS NOT NULL"), "nothing stored");
    expectOk(suspectWithReport(fresh(tooLong), CORRECT_SUSPECT, "\"evidence1\":\"" + "z".repeat(600) + "\""));
    expectOk(suspectWithReport(fresh(withReport), CORRECT_SUSPECT, "\"evidence1\":\"Badge log 9:17 PM\",\"explanation\":\"Line one\\nLine two\""));
    expectOk(suspectWithReport(fresh(withoutReport), CORRECT_SUSPECT, ""));
    for (String name : List.of("WithReport", "WithoutReport", "TooLong")) assertScore(name, "15.00", "10.00", "15.00", "40.00");
    String adminTokenNow = adminToken();
    Response reports = expectOk(get("/admin/final/reports", adminTokenNow));
    assertEquals(List.of("Badge log 9:17 PM"), reports.json("$[?(@.teamName == 'WithReport')].evidence1"));
    assertEquals(List.of("Line one\nLine two"), reports.json("$[?(@.teamName == 'WithReport')].explanation"));
    assertNull(((List<?>) reports.json("$[?(@.teamName == 'WithoutReport')].evidence1")).getFirst(), "an empty report is stored as none");
    // A member only ever reads its own report back; another team's view carries none of it.
    assertEquals("Badge log 9:17 PM", view(fresh(withReport)).json("$.submission.report.evidence1"));
    assertFalse(view(fresh(withoutReport)).body().contains("Badge log"));
    assertEquals(403, get("/admin/final/reports", fresh(withReport).token()).status());
  }

  @Test
  void timer19_aFiveMinutePauseIsNotCountedInTheSubmissionTime() {
    TestTeam alpha = teamWithKeys("Alpha", 4);
    openFinal();
    playTo(alpha, 4);
    clock.advance(Duration.ofMinutes(20));
    expectOk(post("/admin/final/timer/pause", adminToken(), null));
    clock.advance(Duration.ofMinutes(5));
    expectError(suspect(fresh(alpha), CORRECT_SUSPECT), 409, "The final is paused.");
    expectOk(post("/admin/final/timer/resume", adminToken(), null));
    clock.advance(Duration.ofMinutes(10));
    expectOk(suspect(fresh(alpha), CORRECT_SUSPECT));
    assertEquals(1800, count("SELECT active_seconds FROM team_final WHERE team_id = " + alpha.id()), "20 + 10 active minutes");
    assertScore("Alpha", "15.00", "10.00", "10.00", "35.00");
  }

  @Test
  void timer20_at4000_everyActionIsRefusedWithTheExistingMessage_and_no5xx() {
    TestTeam notUnlocked = teamWithKeys("NotUnlocked", 4);
    TestTeam inCase = teamWithKeys("InCase", 4);
    TestTeam inRecovery = teamWithKeys("InRecovery", 2);
    TestTeam done = teamWithKeys("Done", 4);
    openFinal();
    playTo(inCase, 2);
    playTo(done, 4);
    expectOk(suspect(done, CORRECT_SUSPECT));
    clock.advance(Duration.ofMinutes(40));
    expectError(unlockWithKeys(fresh(notUnlocked)), 409, TIME_UP);
    expectError(passphrase(fresh(inCase), 3, PASSPHRASES.get(2)), 409, TIME_UP);
    expectError(mlCheck(fresh(inRecovery), 1, ML_ANSWERS.get(0)), 409, TIME_UP);
    expectError(arduinoRun(fresh(inRecovery), UUID.randomUUID(), arduinoBody(fixedArduinoProgram(), "A")), 409, OVER);
    // Every later call by an already finalised team: the standard message.
    expectError(suspect(fresh(inCase), CORRECT_SUSPECT), 409, OVER);
    expectError(startCase(fresh(inCase)), 409, OVER);
    expectError(suspect(fresh(done), WRONG_SUSPECT), 409, ALREADY_RECORDED);
    for (TestTeam team : List.of(notUnlocked, inCase, inRecovery, done)) {
      Response v = view(fresh(team));
      assertEquals("COMPLETED", v.json("$.status"), team.name());
      assertEquals("SUBMITTED", v.json("$.screen"));
    }
    String adminTokenNow = adminToken();
    expectOk(get("/admin/final/board", adminTokenNow));
    expectOk(get("/admin/scores", adminTokenNow));
    expectOk(get("/admin/final/timer", adminTokenNow));
    expectError(post("/admin/final/timer/pause", adminTokenNow, null), 409, "Time is up for the final; its timer can no longer change.");
  }

  @Test
  void admin21_resettingTheFinal_clearsTeamProgressAndGrantedKeys_andTheFinalCanOpenAgain() {
    TestTeam alpha = teamWithKeys("Alpha", 4);
    TestTeam bravo = teamWithKeys("Bravo", 3);
    openFinal();
    playTo(alpha, 4);
    expectOk(suspect(alpha, CORRECT_SUSPECT));
    finishRecovery(bravo);
    assertEquals(4, count("SELECT count(*) FROM collected_clue WHERE team_id = " + bravo.id()));
    expectOk(post("/admin/reset/final", admin, "{\"reason\":\"Pre-release rehearsal reset\"}"));
    assertEquals(0, count("SELECT count(*) FROM team_final"));
    assertEquals(0, count("SELECT count(*) FROM final_clue_attempt"));
    assertEquals(0, count("SELECT count(*) FROM final_unlock_attempt"));
    assertEquals(0, count("SELECT count(*) FROM recovery_check"));
    assertEquals(0, count("SELECT count(*) FROM team_recovery"));
    assertEquals(3, count("SELECT count(*) FROM collected_clue WHERE team_id = " + bravo.id()), "the key the recovery granted is taken back; the three earned keys stay");
    assertEquals(4, count("SELECT count(*) FROM collected_clue WHERE team_id = " + alpha.id()));
    assertEquals("LOCKED", view(fresh(alpha)).json("$.status"), "the final is closed again");
    openFinal();
    assertEquals("UNLOCK", view(fresh(alpha)).json("$.screen"));
    Response again = view(fresh(bravo));
    assertEquals("RECOVERY", again.json("$.screen"), "bravo starts its recovery from scratch");
    assertEquals(List.of("ML"), again.json("$.recovery.levels"));
    assertScore("Alpha", "0.00", "0.00", "0.00", "0.00");
    assertEquals(0, count("SELECT count(*) FROM final_clue_attempt"));
  }

  @Test
  void access22_beforeTheFinalStarts_notAuthenticated_wrongRole_disqualified_andAnotherTeam() {
    TestTeam alpha = teamWithKeys("Alpha", 4);
    TestTeam bravo = teamWithKeys("Bravo", 3);
    // Before the final opens: reading says LOCKED, every write is refused, nothing is stored.
    assertEquals("LOCKED", view(alpha).json("$.status"));
    expectError(unlockWithKeys(alpha), 409, NOT_OPEN);
    expectError(startCase(alpha), 409, NOT_OPEN);
    expectError(passphrase(alpha, 1, PASSPHRASES.get(0)), 409, NOT_OPEN);
    expectError(suspect(alpha, CORRECT_SUSPECT), 409, NOT_OPEN);
    assertEquals(409, mlCheck(bravo, 1, ML_ANSWERS.get(0)).status());
    assertEquals(0, count("SELECT count(*) FROM final_unlock_attempt") + count("SELECT count(*) FROM final_clue_attempt") + count("SELECT count(*) FROM recovery_check"));
    openFinal();
    // Not signed in / wrong role: 401 / 403 on every team endpoint.
    for (String path : List.of("/team/final", "/team/final/recovery")) {
      assertEquals(401, get(path, null).status(), path);
      assertEquals(403, get(path, admin).status(), path);
      assertEquals(403, get(path, managers.get(RoomCode.AI)).status(), path);
    }
    assertEquals(401, unlockWith(null).status());
    assertEquals(403, unlockWith(admin).status());
    assertEquals(403, unlockWith(managers.get(RoomCode.LINUX)).status());
    // Admin-only data: a participant is refused on every one of these.
    for (String path : List.of("/admin/final/recovery-solutions", "/admin/final/content", "/admin/final/board", "/admin/final/reports", "/admin/final/recovery", "/admin/scores", "/admin/scoreboard", "/admin/final/timer")) {
      assertEquals(401, get(path, null).status(), path);
      assertEquals(403, get(path, alpha.token()).status(), path);
      assertEquals(403, get(path, managers.get(RoomCode.HARDWARE)).status(), path);
    }
    // A team can only ever act on itself: the endpoints take no team id, so bravo cannot touch alpha's progress.
    expectOk(unlockWithKeys(alpha));
    expectError(unlockWithKeys(bravo), 409, "Finish your recovery levels first.");
    assertEquals(1, count("SELECT count(*) FROM final_unlock_attempt WHERE team_id = " + alpha.id()));
    assertEquals(0, count("SELECT count(*) FROM final_unlock_attempt WHERE team_id = " + bravo.id()));
    // A disqualified team keeps nothing it could add.
    expectOk(post("/admin/teams/" + alpha.id() + "/disqualify", admin, "{\"reason\":\"Pre-release check\"}"));
    expectError(startCase(alpha), 409, "Your team has been disqualified. Contact the organizer.");
    expectError(passphrase(alpha, 1, PASSPHRASES.get(0)), 409, "Your team has been disqualified. Contact the organizer.");
  }

  @Test
  void security23_noParticipantResponseCarriesAnAnswer_andTheOrganizersTablesAreAdminOnly() {
    TestTeam alpha = teamWithKeys("Alpha", 4);
    TestTeam bravo = teamWithKeys("Bravo", 3);
    expectOk(uploadFinalPackage(admin, shippedFinalPackage()));
    openFinal();
    List<String> bodies = new ArrayList<>();
    bodies.add(view(alpha).body());
    bodies.add(unlockWithKeys(alpha).body());
    bodies.add(startCase(alpha).body());
    bodies.add(view(alpha).body());
    for (int clue = 1; clue <= 3; clue++) bodies.add(passphrase(alpha, clue, PASSPHRASES.get(clue - 1)).body());
    bodies.add(passphrase(alpha, 4, "not the passphrase").body());
    bodies.add(view(alpha).body());
    bodies.add(passphrase(alpha, 4, PASSPHRASES.get(3)).body());
    bodies.add(view(alpha).body());
    bodies.add(suspect(alpha, WRONG_SUSPECT).body());
    bodies.add(view(alpha).body());
    // The recovery levels: view, a wrong and a right ML stage, the wrong and the right Arduino option.
    bodies.add(view(bravo).body());
    bodies.add(recoveryView(bravo).body());
    bodies.add(mlCheck(bravo, 1, ML_WRONG.get(0)).body());
    clock.advance(Duration.ofSeconds(2));
    bodies.add(mlCheck(fresh(bravo), 1, ML_ANSWERS.get(0)).body());
    bodies.add(recoveryView(fresh(bravo)).body());
    TestTeam delta = teamWithKeys("Delta", 0);
    recordPassedBefore(delta, "ARDUINO", 1);
    bodies.add(recoveryView(delta).body());
    bodies.add(arduinoRun(delta, UUID.randomUUID(), arduinoBody(arduinoProgram(), "A")).body());
    clock.advance(Duration.ofSeconds(3));
    bodies.add(arduinoRun(fresh(delta), UUID.randomUUID(), arduinoBody(fixedArduinoProgram(), rightLetter(fresh(delta)))).body());
    String content = finalContentJson();
    List<String> secrets = new ArrayList<>(PASSPHRASES.stream().map(String::strip).toList());
    for (var m = Pattern.compile("\"passphraseSha256\":\"([0-9a-f]{64})\"").matcher(content); m.find(); ) secrets.add(m.group(1));
    secrets.addAll(List.of("before the door was ever opened", "ends the window", "Which key opens which file", "\"correctSuspect\""));
    for (String body : bodies) {
      assertFalse(FORBIDDEN_FIELDS.matcher(body).find(), "a forbidden field in: " + body.substring(0, Math.min(300, body.length())));
      for (String secret : secrets) assertFalse(body.contains(secret), "a secret leaked: " + secret);
      assertFalse(body.contains("Samer Murad") && body.contains("\"correct\""), "the right suspect is never marked");
    }
    // The suspects sheet never marks the right one: every card has the same fields.
    Response sheet = view(fresh(alpha));
    assertTrue(sheet.body().contains("\"suspects\""));
    // Organizer-only: the solutions and the key table are refused to a participant and absent from participant JSON.
    assertEquals(403, get("/admin/final/recovery-solutions", fresh(bravo).token()).status());
    assertEquals(200, get("/admin/final/recovery-solutions", adminToken()).status());
    assertFalse(recoveryView(fresh(bravo)).body().contains("caseKeys"));
    assertFalse(view(fresh(alpha)).body().contains("caseKeys"));
  }

  // ====================================================================================================
  // Totals
  // ====================================================================================================

  @Test
  void totals_everyRoomAtFifteenAndAPerfectFinalIsExactlyOneHundred_andTheLeaderboardAddsUp() {
    TestTeam alpha = teamWithKeys("Alpha", 4);
    TestTeam bravo = teamWithKeys("Bravo", 4);
    for (RoomCode code : RoomCode.values()) {
      for (TestTeam team : List.of(alpha, bravo)) {
        expectOk(put("/rooms/" + rooms.get(code) + "/manual-scores/" + team.id(), managers.get(code), "{\"score\":" + (team == alpha ? "15" : "14.99") + "}"));
      }
    }
    openFinal();
    playTo(alpha, 4);
    playTo(bravo, 4);
    clock.advance(Duration.ofMinutes(20));
    expectOk(suspect(fresh(alpha), CORRECT_SUSPECT));
    expectOk(suspect(fresh(bravo), CORRECT_SUSPECT));
    String tokenNow = adminToken();
    Response scores = expectOk(get("/admin/scores", tokenNow));
    assertEquals(new BigDecimal("100.00"), new BigDecimal(String.valueOf(((List<?>) scores.json("$[?(@.teamName == 'Alpha')].total")).getFirst())).setScale(2), "4 x 15 + 40");
    assertEquals(new BigDecimal("60.00"), new BigDecimal(String.valueOf(((List<?>) scores.json("$[?(@.teamName == 'Alpha')].roomsTotal")).getFirst())).setScale(2));
    assertEquals(new BigDecimal("99.96"), new BigDecimal(String.valueOf(((List<?>) scores.json("$[?(@.teamName == 'Bravo')].total")).getFirst())).setScale(2), "4 x 14.99 + 40, to the cent");
    Response board = expectOk(get("/admin/board", tokenNow));
    assertEquals(List.of("Alpha", "Bravo"), board.json("$.ranking[*].scores.teamName"));
    assertEquals(List.of(1, 2), board.json("$.ranking[*].rank"));
    List<Number> totals = board.json("$.ranking[*].scores.total");
    List<Number> parts = board.json("$.ranking[*].scores.roomsTotal");
    List<Number> finals = board.json("$.ranking[*].scores.finalScore.total");
    for (int i = 0; i < totals.size(); i++) {
      assertEquals(0, new BigDecimal(totals.get(i).toString()).compareTo(new BigDecimal(parts.get(i).toString()).add(new BigDecimal(finals.get(i).toString()))), "total = rooms + final for row " + i);
    }
  }

  // ====================================================================================================
  // helpers
  // ====================================================================================================

  /** A team holding the first {@code keys} room keys when the final opens (rows inserted directly). */
  private TestTeam teamWithKeys(String name, int keys) {
    GroupCode group = GroupCode.values()[groupCounter++ % GroupCode.values().length];
    TestTeam team = team(name, group);
    RoomCode[] all = RoomCode.values();
    for (int i = 0; i < keys; i++) {
      db.update("INSERT INTO collected_clue(team_id, room_id, source, collected_at) VALUES (?, ?, 'MANUAL', now()) ON CONFLICT DO NOTHING", team.id(), rooms.get(all[i]));
    }
    return team;
  }

  private Response view(TestTeam team) {
    return expectOk(get("/team/final", team.token()));
  }

  /** A new session for the team (the manual clock moved past the old token's life). */
  private TestTeam fresh(TestTeam team) {
    String email = db.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, team.accountId());
    return new TestTeam(team.id(), team.name(), team.group(), team.accountId(), login(email));
  }

  private String adminToken() {
    return login(db.queryForObject("SELECT email FROM app_user WHERE role = 'ADMIN' LIMIT 1", String.class));
  }

  private Response adminBoard() {
    return expectOk(get("/admin/final/board", adminToken()));
  }

  private int count(String sql) {
    return db.queryForObject(sql, Integer.class);
  }

  private Response unlockWith(String token) {
    return call(HttpMethod.POST, "/team/final/unlock", token, "{\"codes\":[\"a\",\"b\",\"c\",\"d\"]}", UUID.randomUUID().toString());
  }

  /** The admin's breakdown of one team's final: clues, suspect, time and total, all to 2 decimals. */
  private void assertScore(String team, String clues, String suspect, String time, String total) {
    String token = adminToken();
    Response scores = expectOk(get("/admin/scores", token));
    String row = "$[?(@.teamName == '" + team + "')].finalScore.";
    assertEquals(new BigDecimal(clues), decimal(scores, row + "clues"), team + " clues");
    assertEquals(new BigDecimal(suspect), decimal(scores, row + "suspect"), team + " suspect");
    assertEquals(new BigDecimal(time), decimal(scores, row + "time"), team + " time");
    assertEquals(new BigDecimal(total), decimal(scores, row + "total"), team + " total");
    Response board = expectOk(get("/admin/final/board", token));
    String brow = "$[?(@.teamName == '" + team + "')].";
    assertEquals(new BigDecimal(clues), decimal(board, brow + "cluePoints"), team + " board clues");
    assertEquals(new BigDecimal(suspect), decimal(board, brow + "suspectPoints"), team + " board suspect");
    assertEquals(new BigDecimal(time), decimal(board, brow + "timePoints"), team + " board time");
    assertEquals(new BigDecimal(total), decimal(board, brow + "finalScore"), team + " board total");
  }

  private static BigDecimal decimal(Response response, String path) {
    List<?> found = response.json(path);
    return new BigDecimal(String.valueOf(found.getFirst())).setScale(2);
  }

  /** The six ML stages through the API with the right answers (one check per 2 seconds, on the manual clock). */
  private void playMlThroughTheApi(TestTeam team) {
    for (int stage = 1; stage <= 6; stage++) {
      clock.advance(Duration.ofSeconds(2));
      Response result = expectOk(mlCheck(team, stage, ML_ANSWERS.get(stage - 1)));
      assertEquals(true, result.json("$.passed"), "stage " + stage + ": " + result.body());
    }
  }

  private Response suspectWithReport(TestTeam team, String suspectId, String report) {
    return teamPost(team, "/team/final/suspect", UUID.randomUUID(), "{\"suspectId\":" + quote(suspectId) + (report.isEmpty() ? "" : "," + report) + "}");
  }

  private static byte[] shippedFinalPackage() {
    try {
      return Files.readAllBytes(Path.of("..", "docs", "final", "last-shipment", "final-package.zip"));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private Response uploadFinalPackage(String token, byte[] zip) {
    var builder = multipart(HttpMethod.PUT, "/api/v1/event" + FINAL_BUNDLE).file(new MockMultipartFile("file", "package.zip", "application/zip", zip));
    builder.header("Authorization", "Bearer " + token);
    try {
      var response = mvc.perform(builder).andReturn().getResponse();
      return new Response(response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private int download(String path, String token) {
    try {
      return mvc
        .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/event" + path).header("Authorization", "Bearer " + token))
        .andReturn()
        .getResponse()
        .getStatus();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Starts every call at the same moment and returns their responses in order. */
  private List<Response> runTogether(List<Callable<Response>> calls) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(calls.size());
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Response>> futures = new ArrayList<>();
      for (Callable<Response> call : calls) {
        futures.add(pool.submit(() -> {
          go.await();
          return call.call();
        }));
      }
      go.countDown();
      List<Response> results = new ArrayList<>();
      for (Future<Response> future : futures) results.add(future.get());
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  /** Not used by the tests above; kept so a failing assertion can print a team's whole final state. */
  @SuppressWarnings("unused")
  private Map<String, Object> dump(TestTeam team) {
    return db.queryForMap("SELECT * FROM team_final WHERE team_id = ?", team.id());
  }
}
