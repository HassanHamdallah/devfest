package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * Hints (-2) and cheating (-7.5): who may record and cancel them, the 2-hint cap across rooms, one active cheating
 * violation per team and room, the append-only log, and what a team may see.
 */
class EventDeductionTest extends EventIntegrationTest {

  private static final String REASON = "Copied the neighbour's output";

  private TestTeam alpha;
  private TestTeam bravo;
  private String aiManager;
  private String hwManager;
  private long ai;
  private long hardware;

  @BeforeEach
  void createTeams() {
    loadContent();
    alpha = team("Alpha", GroupCode.A);
    bravo = team("Bravo", GroupCode.B);
    aiManager = managers.get(RoomCode.AI);
    hwManager = managers.get(RoomCode.HARDWARE);
    ai = rooms.get(RoomCode.AI);
    hardware = rooms.get(RoomCode.HARDWARE);
  }

  // ---- hints -------------------------------------------------------------------------------------

  @Test
  void aHintIsOneClickWithAnOptionalNote() {
    setRound(1);
    Response noNote = expectOk(hint(aiManager, RoomCode.AI, alpha, null));
    assertEquals("HINT", noNote.json("$.kind"));
    assertEquals("", noNote.json("$.reason"));
    Response withNote = expectOk(hint(aiManager, RoomCode.AI, alpha, "Pointed at the log"));
    assertEquals("Pointed at the log", withNote.json("$.reason"));
    Response board = expectOk(get("/rooms/" + ai + "/board", aiManager));
    List<Integer> used = board.json("$.teams[?(@.teamName == 'Alpha')].hintsUsed");
    List<Integer> inRoom = board.json("$.teams[?(@.teamName == 'Alpha')].roomHints");
    List<Integer> allowed = board.json("$.teams[?(@.teamName == 'Alpha')].hintsAllowed");
    assertEquals(List.of(2), used);
    assertEquals(List.of(2), inRoom);
    assertEquals(List.of(2), allowed);
  }

  @Test
  void aTeamHasTwoHintsAcrossAllFourRoomsAndTheThirdIsRefused() {
    setRound(1);
    expectOk(hint(aiManager, RoomCode.AI, alpha, null));
    expectOk(hint(admin, RoomCode.LINUX, alpha, null));
    expectError(hint(aiManager, RoomCode.AI, alpha, null), 409, "This team has already used its 2 hints.");
    expectError(hint(admin, RoomCode.HARDWARE, alpha, null), 409, "This team has already used its 2 hints.");
    // The other team is unaffected, and its card says "n of 2" for itself.
    expectOk(hint(managers.get(RoomCode.PROBLEM_SOLVING), RoomCode.PROBLEM_SOLVING, bravo, null));
    assertEquals(2, (int) expectOk(get("/team/board", alpha.token())).json("$.hintsUsed"));
    assertEquals(1, (int) expectOk(get("/team/board", bravo.token())).json("$.hintsUsed"));
    assertEquals(2, (int) expectOk(get("/team/board", bravo.token())).json("$.hintsAllowed"));
  }

  @Test
  void aCancelledHintIsRestoredAndStaysInTheLog() {
    setRound(1);
    long first = id(expectOk(hint(aiManager, RoomCode.AI, alpha, null)));
    expectOk(hint(aiManager, RoomCode.AI, alpha, null));
    expectError(hint(aiManager, RoomCode.AI, alpha, null), 409, "This team has already used its 2 hints.");
    expectError(cancel(aiManager, first, ""), 400, "Please check the form fields.");
    expectError(cancel(aiManager, first, "no"), 400, "Give a reason (3 to 500 characters).");
    Response cancelled = expectOk(cancel(aiManager, first, "Pressed by mistake"));
    assertEquals(true, cancelled.json("$.cancelled"));
    assertEquals("Pressed by mistake", cancelled.json("$.cancelReason"));
    assertEquals("MANAGER 2", cancelled.json("$.cancelledByName"));
    expectError(cancel(aiManager, first, "Again"), 409, "This deduction is already cancelled.");
    assertEquals(1, (int) expectOk(get("/team/board", alpha.token())).json("$.hintsUsed"));
    expectOk(hint(aiManager, RoomCode.AI, alpha, null));
    assertEquals(2, (int) expectOk(get("/team/board", alpha.token())).json("$.hintsUsed"));
    Response log = expectOk(get("/rooms/" + ai + "/deductions", aiManager));
    assertEquals(3, (int) log.json("$.length()"), "the cancelled row stays in the log");
    assertEquals(1, ((List<?>) log.json("$[?(@.cancelled == true)]")).size());
  }

  @Test
  void concurrentHintsNeverExceedTwo() throws Exception {
    setRound(1);
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      List<Callable<Integer>> calls = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        String token = i % 2 == 0 ? aiManager : admin;
        calls.add(() -> hint(token, RoomCode.AI, alpha, null).status());
      }
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> future : pool.invokeAll(calls)) statuses.add(future.get());
      assertEquals(2, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
      assertEquals(4, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
    } finally {
      pool.shutdownNow();
    }
    assertEquals(2, count("SELECT count(*) FROM score_deduction WHERE kind = 'HINT' AND cancelled_at IS NULL"));
  }

  // ---- cheating ----------------------------------------------------------------------------------

  @Test
  void cheatingNeedsConfirmationAndAReasonAndOnlyOneActiveViolationPerTeamAndRoom() {
    setRound(1);
    expectError(cheating(aiManager, RoomCode.AI, alpha, REASON, false), 400, "Confirm the cheating violation first.");
    expectError(cheating(aiManager, RoomCode.AI, alpha, "", true), 400, "Please check the form fields.");
    expectError(cheating(aiManager, RoomCode.AI, alpha, "ab", true), 400, "Give a reason (3 to 500 characters).");
    Response recorded = expectOk(cheating(aiManager, RoomCode.AI, alpha, REASON, true));
    assertEquals("CHEATING", recorded.json("$.kind"));
    assertEquals(REASON, recorded.json("$.reason"));
    expectError(cheating(aiManager, RoomCode.AI, alpha, REASON, true), 409, "This team already has an active cheating violation in this room.");
    expectError(cheating(admin, RoomCode.AI, alpha, REASON, true), 409, "This team already has an active cheating violation in this room.");
    // A different room is a different violation; so is a different team.
    expectOk(cheating(admin, RoomCode.LINUX, alpha, REASON, true));
    expectOk(cheating(managers.get(RoomCode.PROBLEM_SOLVING), RoomCode.PROBLEM_SOLVING, bravo, REASON, true));
    // Once cancelled, a new violation may be recorded again.
    expectOk(cancel(aiManager, id(recorded), "Recorded for the wrong team"));
    expectOk(cheating(aiManager, RoomCode.AI, alpha, REASON, true));
    assertEquals(2, count("SELECT count(*) FROM score_deduction WHERE room_id = " + ai + " AND team_id = " + alpha.id()));
  }

  @Test
  void deductionsSubtractPointsFromTheRoomScoreAndCancellingRestoresThem() {
    setRound(4);
    expectOk(put("/rooms/" + hardware + "/manual-scores/" + alpha.id(), hwManager, "{\"score\":15}"));
    assertEquals(new BigDecimal("15.00"), roomScore("Alpha", 3));
    long hint = id(expectOk(hint(hwManager, RoomCode.HARDWARE, alpha, null)));
    assertEquals(new BigDecimal("13.00"), roomScore("Alpha", 3));
    long violation = id(expectOk(cheating(hwManager, RoomCode.HARDWARE, alpha, REASON, true)));
    assertEquals(new BigDecimal("5.50"), roomScore("Alpha", 3));
    expectOk(cancel(hwManager, hint, "Not a hint after all"));
    assertEquals(new BigDecimal("7.50"), roomScore("Alpha", 3));
    expectOk(cancel(hwManager, violation, "Not cheating after all"));
    assertEquals(new BigDecimal("15.00"), roomScore("Alpha", 3));
    // The floor at zero.
    expectOk(put("/rooms/" + hardware + "/manual-scores/" + alpha.id(), hwManager, "{\"score\":3}"));
    expectOk(cheating(hwManager, RoomCode.HARDWARE, alpha, REASON, true));
    assertEquals(new BigDecimal("0.00"), roomScore("Alpha", 3));
  }

  @Test
  void deductionsNeverChangeCluesOrQualification() {
    collectClues(alpha, RoomCode.values());
    setRound(5);
    openFinal();
    assertEquals("IN_FINAL", expectOk(get("/team/final", alpha.token())).json("$.status"));
    int clues = count("SELECT count(*) FROM collected_clue WHERE team_id = " + alpha.id());
    expectOk(hint(admin, RoomCode.AI, alpha, null));
    expectOk(cheating(admin, RoomCode.AI, alpha, REASON, true));
    assertEquals(clues, count("SELECT count(*) FROM collected_clue WHERE team_id = " + alpha.id()));
    assertEquals("IN_FINAL", expectOk(get("/team/final", alpha.token())).json("$.status"));
    assertEquals(4, (int) expectOk(get("/team/final", alpha.token())).json("$.cluesCollected"));
  }

  // ---- who may ----------------------------------------------------------------------------------

  @Test
  void onlyTheRoomsManagerOrTheAdminMayRecordForATeamInThatRoom() {
    setRound(1);
    String ps = managers.get(RoomCode.PROBLEM_SOLVING);
    // Another room's manager: the room is not theirs.
    expectError(hint(ps, RoomCode.AI, alpha, null), 404, "Room not found.");
    expectError(cheating(ps, RoomCode.AI, alpha, REASON, true), 404, "Room not found.");
    // Bravo is in the Problem solving room in round 1, not in the AI room: the AI manager cannot touch it.
    expectError(hint(aiManager, RoomCode.AI, bravo, null), 404, "Team not found.");
    expectError(cheating(aiManager, RoomCode.AI, bravo, REASON, true), 404, "Team not found.");
    // A member has no business here.
    assertEquals(403, hint(alpha.token(), RoomCode.AI, alpha, null).status());
    assertEquals(403, cheating(alpha.token(), RoomCode.AI, alpha, REASON, true).status());
    assertEquals(401, hint(null, RoomCode.AI, alpha, null).status());
    // The admin may record for any team and room, also one that is not in that room now.
    expectOk(hint(admin, RoomCode.AI, bravo, null));
    expectOk(cheating(admin, RoomCode.HARDWARE, bravo, REASON, true));
    assertEquals(0, count("SELECT count(*) FROM score_deduction WHERE team_id = " + alpha.id()));
    expectError(hint(admin, RoomCode.AI, new TestTeam(Long.MAX_VALUE, "x", GroupCode.A, 0, ""), null), 404, "Team not found.");
  }

  @Test
  void aTeamMovedByAnExceptionIsInThatRoomForItsManager() {
    setRound(1);
    expectError(hint(managers.get(RoomCode.LINUX), RoomCode.LINUX, alpha, null), 404, "Team not found.");
    String body = "{\"teamId\":%d,\"roomId\":%d,\"reason\":\"Sent to the Linux room today\"}".formatted(alpha.id(), rooms.get(RoomCode.LINUX));
    expectOk(put("/admin/overrides/room-exception", admin, body));
    expectOk(hint(managers.get(RoomCode.LINUX), RoomCode.LINUX, alpha, null));
    expectError(hint(aiManager, RoomCode.AI, alpha, null), 404, "Team not found.");
  }

  @Test
  void onlyTheRoomsManagerOrTheAdminMayCancelAndAManagerOnlyForATeamInTheRoom() {
    setRound(1);
    long inAi = id(expectOk(hint(aiManager, RoomCode.AI, alpha, null)));
    long inPs = id(expectOk(hint(managers.get(RoomCode.PROBLEM_SOLVING), RoomCode.PROBLEM_SOLVING, bravo, null)));
    expectError(cancel(aiManager, inPs, "Not my room"), 404, "Deduction not found.");
    assertEquals(403, cancel(alpha.token(), inAi, "I want it gone").status());
    assertEquals(401, cancel(null, inAi, "Anonymous").status());
    expectError(cancel(aiManager, Long.MAX_VALUE, "Nothing here"), 404, "Deduction not found.");
    // Round 2: Alpha has left the AI room, so its manager no longer acts for it; the admin always may.
    setRound(2);
    expectError(cancel(aiManager, inAi, "Too late for me"), 404, "Deduction not found.");
    expectOk(cancel(admin, inAi, "Admin correction"));
  }

  // ---- the log ----------------------------------------------------------------------------------

  @Test
  void theLogIsPerRoomForManagersAndCompleteForTheAdmin() {
    setRound(1);
    expectOk(hint(aiManager, RoomCode.AI, alpha, null));
    expectOk(cheating(managers.get(RoomCode.PROBLEM_SOLVING), RoomCode.PROBLEM_SOLVING, bravo, REASON, true));
    Response aiLog = expectOk(get("/rooms/" + ai + "/deductions", aiManager));
    assertEquals(1, (int) aiLog.json("$.length()"));
    assertEquals("AI", aiLog.json("$[0].roomCode"));
    assertEquals("Alpha", aiLog.json("$[0].teamName"));
    assertFalse(aiLog.body().contains(REASON));
    Response all = expectOk(get("/admin/deductions", admin));
    assertEquals(2, (int) all.json("$.length()"));
    assertTrue(all.body().contains(REASON));
    expectError(get("/rooms/" + rooms.get(RoomCode.PROBLEM_SOLVING) + "/deductions", aiManager), 404, "Room not found.");
    assertEquals(403, get("/admin/deductions", aiManager).status());
    assertEquals(403, get("/admin/deductions", alpha.token()).status());
    assertEquals(403, get("/rooms/" + ai + "/deductions", alpha.token()).status());
    assertEquals(1, (int) expectOk(get("/rooms/" + ai + "/deductions", admin)).json("$.length()"), "the admin may read a room's log too");
  }

  @Test
  void theLogIsAppendOnlyInTheDatabase() {
    setRound(1);
    long hint = id(expectOk(hint(aiManager, RoomCode.AI, alpha, null)));
    assertThrows(DataAccessException.class, () -> db.update("DELETE FROM score_deduction WHERE id = ?", hint));
    assertThrows(DataAccessException.class, () -> db.update("UPDATE score_deduction SET reason = 'edited' WHERE id = ?", hint));
    assertThrows(DataAccessException.class, () -> db.update("UPDATE score_deduction SET kind = 'CHEATING', reason = 'edited reason' WHERE id = ?", hint));
    expectOk(cancel(aiManager, hint, "Cancelled for the test"));
    assertThrows(DataAccessException.class, () -> db.update("UPDATE score_deduction SET cancel_reason = 'rewritten' WHERE id = ?", hint));
    assertEquals(1, count("SELECT count(*) FROM score_deduction"));
  }

  @Test
  void theRetiredPenaltyKindsAndEndpointsAreGone() {
    setRound(1);
    for (String kind : List.of("MINOR_VIOLATION", "PROHIBITED_TOOL", "ANSWER_SHARING", "WRONG_ACCUSATION", "RECOVERY", "TIME_ADJUSTMENT", "HINT_MINUTES")) {
      Response retired = post("/rooms/" + ai + "/penalties", aiManager, "{\"teamId\":%d,\"kind\":\"%s\",\"note\":\"\"}".formatted(alpha.id(), kind));
      assertTrue(retired.status() == 404 || retired.status() == 405, kind + " -> " + retired.status());
    }
    assertEquals(404, post("/admin/overrides/time-adjustment", admin, "{\"teamId\":1,\"minutes\":-5,\"reason\":\"No longer exists\"}").status());
    assertEquals(0, count("SELECT count(*) FROM information_schema.tables WHERE table_name = 'penalty'"));
    assertThrows(DataAccessException.class, () ->
      db.update("INSERT INTO score_deduction(team_id, room_id, kind, reason, created_by, created_at) VALUES (?, ?, 'MINOR_VIOLATION', 'x y z', ?, now())", alpha.id(), ai, managerIds.get(RoomCode.AI))
    );
  }

  // ---- what a team sees -------------------------------------------------------------------------

  @Test
  void aTeamSeesOnlyItsHintCountNeverACheatingRecordADeductionOrAScore() {
    setRound(4);
    expectOk(put("/rooms/" + hardware + "/manual-scores/" + alpha.id(), hwManager, "{\"score\":14.5}"));
    expectOk(hint(hwManager, RoomCode.HARDWARE, alpha, "A private hint note"));
    expectOk(cheating(hwManager, RoomCode.HARDWARE, alpha, REASON, true));
    for (String path : List.of("/team/board", "/team/final", "/home", "/team/submissions", "/revision")) {
      String body = expectOk(get(path, alpha.token())).body();
      assertFalse(body.contains(REASON), path);
      assertFalse(body.contains("A private hint note"), path);
      assertFalse(body.toUpperCase().contains("CHEATING"), path);
      assertFalse(body.contains("14.5"), path);
      assertFalse(body.contains("\"score"), path);
    }
    assertEquals(1, (int) expectOk(get("/team/board", alpha.token())).json("$.hintsUsed"));
    expectError(get("/results", alpha.token()), 403, "Results are not published yet.");
    expectOk(put("/admin/publication", admin, "{\"published\":true}"));
    String published = expectOk(get("/results", alpha.token())).body();
    assertFalse(published.contains(REASON));
    assertFalse(published.toUpperCase().contains("CHEATING"));
    assertFalse(published.contains("deduction"));
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private Response hint(String token, RoomCode room, TestTeam team, String note) {
    String body = note == null ? "{\"teamId\":%d}".formatted(team.id()) : "{\"teamId\":%d,\"note\":%s}".formatted(team.id(), quote(note));
    return post("/rooms/" + rooms.get(room) + "/hints", token, body);
  }

  private Response cheating(String token, RoomCode room, TestTeam team, String reason, boolean confirmed) {
    String body = "{\"teamId\":%d,\"reason\":%s,\"confirmed\":%s}".formatted(team.id(), quote(reason), confirmed);
    return post("/rooms/" + rooms.get(room) + "/cheating", token, body);
  }

  private Response cancel(String token, long deductionId, String reason) {
    return post("/deductions/" + deductionId + "/cancel", token, "{\"reason\":" + quote(reason) + "}");
  }

  private static long id(Response response) {
    return ((Number) response.json("$.id")).longValue();
  }

  private int count(String sql) {
    return db.queryForObject(sql, Integer.class);
  }

  /** The team's score in the room at {@code index} (room position order), from the admin's breakdown. */
  private BigDecimal roomScore(String teamName, int index) {
    Response scores = expectOk(get("/admin/scores", admin));
    List<Number> found = scores.json("$[?(@.teamName == '" + teamName + "')].rooms[" + index + "].score");
    return new BigDecimal(found.get(0).toString()).setScale(2);
  }
}
