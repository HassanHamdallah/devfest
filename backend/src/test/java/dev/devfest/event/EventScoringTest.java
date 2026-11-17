package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * The four room scores through the real read service: the AI base, the typed score of a room without an automatic
 * score, the deductions applied on top, the authorization matrix of the new endpoints, and resets and snapshots.
 */
class EventScoringTest extends EventIntegrationTest {

  private TestTeam alpha;
  private TestTeam bravo;
  private long ai;
  private long hardware;

  @BeforeEach
  void prepare() {
    loadContent();
    alpha = team("Alpha", GroupCode.A);
    bravo = team("Bravo", GroupCode.B);
    ai = rooms.get(RoomCode.AI);
    hardware = rooms.get(RoomCode.HARDWARE);
  }

  // ---- the AI room's base -----------------------------------------------------------------------

  @Test
  void theAiRoomBaseIsItsTotalTimesZeroPointFifteen() {
    expectOk(put("/admin/rooms/" + ai + "/activity", admin, "{\"activity\":\"AI_QUIZ\"}"));
    aiStages(alpha, 99);
    assertEquals(new BigDecimal("14.85"), roomPart("Alpha", 0, "base"));
    assertEquals("AI", roomSource("Alpha", 0));
    assertEquals(new BigDecimal("14.85"), roomPart("Alpha", 0, "score"));
    assertEquals(new BigDecimal("0.00"), roomPart("Bravo", 0, "base"));
    assignRound(1);
    expectOk(post("/rooms/" + ai + "/hints", managers.get(RoomCode.AI), "{\"teamId\":" + alpha.id() + "}"));
    assertEquals(new BigDecimal("12.85"), roomPart("Alpha", 0, "score"));
    expectOk(post("/rooms/" + ai + "/cheating", managers.get(RoomCode.AI), "{\"teamId\":%d,\"reason\":\"Used a second laptop\",\"confirmed\":true}".formatted(alpha.id())));
    assertEquals(new BigDecimal("5.35"), roomPart("Alpha", 0, "score"));
    assertEquals(new BigDecimal("14.85"), roomPart("Alpha", 0, "base"), "the base never changes");
    assertEquals(new BigDecimal("2.00"), roomPart("Alpha", 0, "hintDeduction"));
    assertEquals(new BigDecimal("7.50"), roomPart("Alpha", 0, "cheatingDeduction"));
    assertEquals(new BigDecimal("5.35"), total("Alpha"));
  }

  @Test
  void aTeamWithoutAnyWorkScoresZeroInEveryRoomAndInTheFinal() {
    Response scores = expectOk(get("/admin/scores", admin));
    assertEquals(0.0, ((Number) scores.json("$[0].total")).doubleValue());
    assertEquals(List.of("MANUAL", "MANUAL", "MANUAL", "MANUAL"), scores.json("$[0].rooms[*].source"), "no activity is on in a fresh test event");
    assertEquals("LOCKED", scores.json("$[0].finalScore.status"));
  }

  // ---- the typed score --------------------------------------------------------------------------

  @Test
  void aRoomWithoutAnAutomaticScoreTakesATypedScoreFromItsManagerOrTheAdmin() {
    String hw = managers.get(RoomCode.HARDWARE);
    String path = "/rooms/" + hardware + "/manual-scores/" + alpha.id();
    Response saved = expectOk(put(path, hw, "{\"score\":12.5}"));
    assertEquals(12.5, ((Number) saved.json("$.score")).doubleValue());
    assertEquals(new BigDecimal("12.50"), roomPart("Alpha", 3, "base"));
    expectOk(put(path, admin, "{\"score\":15}"));
    assertEquals(new BigDecimal("15.00"), roomPart("Alpha", 3, "score"));
    expectOk(put(path, hw, "{\"score\":0}"));
    assertEquals(new BigDecimal("0.00"), roomPart("Alpha", 3, "score"));
    expectError(put(path, hw, "{\"score\":15.01}"), 400, "The score must be from 0 to 15.");
    expectError(put(path, hw, "{\"score\":-1}"), 400, "The score must be from 0 to 15.");
    expectError(put(path, hw, "{\"score\":7.123}"), 400, "The score can have at most 2 decimals.");
    assertEquals(400, put(path, hw, "{}").status());
    expectError(put("/rooms/" + hardware + "/manual-scores/" + Long.MAX_VALUE, hw, "{\"score\":1}"), 404, "Team not found.");
    List<String> audit = db.queryForList("SELECT target FROM audit_event WHERE action = 'EVENT_MANUAL_SCORE' ORDER BY id", String.class);
    assertEquals(
      List.of("room:%d team:%d score:12.50".formatted(hardware, alpha.id()), "room:%d team:%d score:15.00".formatted(hardware, alpha.id()), "room:%d team:%d score:0.00".formatted(hardware, alpha.id())),
      audit,
      "every change is audited with its value"
    );
  }

  @Test
  void aRoomThatScoresItselfRefusesATypedScore() {
    expectOk(put("/admin/rooms/" + ai + "/activity", admin, "{\"activity\":\"AI_QUIZ\"}"));
    expectError(put("/rooms/" + ai + "/manual-scores/" + alpha.id(), managers.get(RoomCode.AI), "{\"score\":10}"), 409, "This room's score comes from its activity; it cannot be typed in.");
    assertEquals(0, count("SELECT count(*) FROM room_manual_score"));
  }

  // ---- authorization matrix ---------------------------------------------------------------------

  private record Case(String name, String method, String path, String body, Integer member, Integer otherManager, Integer ownManager, Integer admin) {}

  /** Each new endpoint against each role: 401 anonymous, 403 wrong role, 404 another room's resource, 200 allowed. */
  @Test
  void theNewEndpointsFollowTheAuthorizationMatrix() {
    setRound(1);
    String hint = "{\"teamId\":" + alpha.id() + "}";
    String cheating = "{\"teamId\":%d,\"reason\":\"Peeked at another screen\",\"confirmed\":true}".formatted(alpha.id());
    List<Case> cases = List.of(
      new Case("hint", "POST", "/rooms/" + ai + "/hints", hint, 403, 404, 200, 200),
      new Case("cheating", "POST", "/rooms/" + ai + "/cheating", cheating, 403, 404, 200, 409),
      new Case("room log", "GET", "/rooms/" + ai + "/deductions", null, 403, 404, 200, 200),
      new Case("full log", "GET", "/admin/deductions", null, 403, 403, 403, 200),
      new Case("manual score", "PUT", "/rooms/" + hardware + "/manual-scores/" + alpha.id(), "{\"score\":5}", 403, 404, 404, 200),
      new Case("scores", "GET", "/admin/scores", null, 403, 403, 403, 200),
      new Case("final content", "GET", "/admin/final/content", null, 403, 403, 403, 200),
      new Case("final timer", "GET", "/admin/final/timer", null, 403, 403, 403, 200),
      new Case("timer pause", "POST", "/admin/final/timer/pause", null, 403, 403, 403, 409)
    );
    for (Case c : cases) {
      assertEquals(401, run(c, null).status(), c.name() + " anonymous");
      assertEquals(c.member(), run(c, alpha.token()).status(), c.name() + " member");
      assertEquals(c.otherManager(), run(c, managers.get(RoomCode.PROBLEM_SOLVING)).status(), c.name() + " other room's manager");
    }
    // The manager of the room is allowed; the admin goes last (its repeat of the same cheating violation is a 409 by design).
    for (Case c : cases) {
      if (c.ownManager() == 200) assertEquals(200, run(c, managers.get(RoomCode.AI)).status(), c.name() + " own manager");
    }
    for (Case c : cases) assertEquals(c.admin(), run(c, admin).status(), c.name() + " admin");
    // A cancellation: members 403, another room's manager 404, the room's manager and the admin allowed.
    long deduction = db.queryForObject("SELECT id FROM score_deduction WHERE kind = 'HINT' ORDER BY id LIMIT 1", Long.class);
    String cancel = "{\"reason\":\"Cancelled by the matrix test\"}";
    assertEquals(401, post("/deductions/" + deduction + "/cancel", null, cancel).status());
    assertEquals(403, post("/deductions/" + deduction + "/cancel", alpha.token(), cancel).status());
    assertEquals(404, post("/deductions/" + deduction + "/cancel", managers.get(RoomCode.PROBLEM_SOLVING), cancel).status());
    assertEquals(200, post("/deductions/" + deduction + "/cancel", managers.get(RoomCode.AI), cancel).status());
  }

  private Response run(Case c, String token) {
    return switch (c.method()) {
      case "GET" -> get(c.path(), token);
      case "PUT" -> put(c.path(), token, c.body());
      default -> post(c.path(), token, c.body());
    };
  }

  @Test
  void theTeamEndpointsOfTheFinalAreForTeamsOnly() {
    List<String> posts = List.of("/team/final/unlock", "/team/final/start", "/team/final/clues/1/passphrase", "/team/final/suspect", "/team/final/recovery");
    for (String path : posts) {
      String body = switch (path) {
        case "/team/final/unlock" -> "{\"codes\":[\"a\",\"b\",\"c\",\"d\"]}";
        case "/team/final/clues/1/passphrase" -> "{\"passphrase\":\"x\"}";
        case "/team/final/suspect" -> "{\"suspectId\":\"S01\"}";
        case "/team/final/recovery" -> "{\"answer\":\"x\"}";
        default -> null;
      };
      assertEquals(401, call(org.springframework.http.HttpMethod.POST, path, null, body, UUID.randomUUID().toString()).status(), path + " anonymous");
      assertEquals(403, call(org.springframework.http.HttpMethod.POST, path, admin, body, UUID.randomUUID().toString()).status(), path + " admin");
      assertEquals(403, call(org.springframework.http.HttpMethod.POST, path, managers.get(RoomCode.AI), body, UUID.randomUUID().toString()).status(), path + " manager");
    }
    assertEquals(403, get("/team/final", admin).status());
    assertEquals(403, get("/team/final", managers.get(RoomCode.AI)).status());
  }

  // ---- the admin's scoreboard and live final table ----------------------------------------------

  @Test
  void theScoreboardAndTheLiveFinalTableShowEveryTeamToTheAdminOnly() {
    for (RoomCode code : RoomCode.values()) {
      db.update("INSERT INTO collected_clue(team_id, room_id, source, collected_at) VALUES (?, ?, 'MANUAL', now())", alpha.id(), rooms.get(code));
    }
    expectOk(put("/rooms/" + hardware + "/manual-scores/" + alpha.id(), managers.get(RoomCode.HARDWARE), "{\"score\":11}"));
    setRound(5);
    openFinal();
    expectOk(unlock(alpha, "a", "b", "c", "d"));
    playTo(alpha, 3);
    expectOk(passphrase(alpha, 4, "not the passphrase"));
    String statuses = "$[?(@.teamName == 'Alpha')]";

    Response board = expectOk(get("/admin/final/board", admin));
    assertEquals(List.of("IN_FINAL"), board.json(statuses + ".status"));
    assertEquals(List.of("Case"), board.json(statuses + ".stage"));
    assertEquals(List.of(4), board.json(statuses + ".clues"));
    assertEquals(List.of(3), board.json(statuses + ".cluesSolved"));
    assertEquals(List.of(2), board.json(statuses + ".unlockAttempts"));
    assertEquals(List.of(1), board.json(statuses + ".unlockWrong"));
    assertEquals(List.of(4), board.json(statuses + ".passphraseAttempts"));
    assertEquals(List.of(1), board.json(statuses + ".passphraseWrong"));
    assertEquals(List.of(11.25), board.json(statuses + ".cluePoints"));
    assertNull(((List<?>) board.json(statuses + ".suspect")).get(0), "no suspect yet");
    assertNull(((List<?>) board.json(statuses + ".suspectCorrect")).get(0));
    assertEquals(List.of("NOT_QUALIFIED"), board.json("$[?(@.teamName == 'Bravo')].status"));
    assertEquals(List.of("Not qualified"), board.json("$[?(@.teamName == 'Bravo')].stage"));

    Response scoreboard = expectOk(get("/admin/scoreboard", admin));
    assertEquals(List.of("Alpha", "Bravo"), scoreboard.json("$[*].scores.teamName"));
    assertEquals(List.of(1, 2), scoreboard.json("$[*].rank"));
    assertEquals(4, (int) scoreboard.json("$[0].scores.rooms.length()"));
    for (String path : List.of("/admin/final/board", "/admin/scoreboard")) {
      assertEquals(403, get(path, alpha.token()).status(), path);
      assertEquals(403, get(path, managers.get(RoomCode.AI)).status(), path);
      assertEquals(401, get(path, null).status(), path);
    }
    // After the suspect is submitted the table names it, with its correctness, the active time and the finish time.
    expectOk(passphrase(alpha, 4, PASSPHRASES.get(3)));
    openInvestigationPhase();
    expectOk(suspect(alpha, CORRECT_SUSPECT));
    Response done = expectOk(get("/admin/final/board", admin));
    assertEquals(List.of("Submitted"), done.json(statuses + ".stage"));
    assertEquals(List.of(4), done.json(statuses + ".cluesSolved"));
    assertEquals(List.of("Samer Murad"), done.json(statuses + ".suspect"));
    assertEquals(List.of(true), done.json(statuses + ".suspectCorrect"));
    assertEquals(List.of(40.0), done.json(statuses + ".finalScore"));
    assertEquals(List.of("SUBMITTED"), done.json(statuses + ".finishedBy"));
  }

  // ---- resets and snapshots ---------------------------------------------------------------------

  @Test
  void resettingATeamOrTheEventClearsTheNewTablesAfterASnapshot() {
    setRound(1);
    expectOk(post("/rooms/" + ai + "/hints", managers.get(RoomCode.AI), "{\"teamId\":" + alpha.id() + "}"));
    expectOk(post("/rooms/" + ai + "/cheating", managers.get(RoomCode.AI), "{\"teamId\":%d,\"reason\":\"Used notes\",\"confirmed\":true}".formatted(alpha.id())));
    expectOk(put("/rooms/" + hardware + "/manual-scores/" + alpha.id(), managers.get(RoomCode.HARDWARE), "{\"score\":9}"));
    expectOk(put("/rooms/" + hardware + "/manual-scores/" + bravo.id(), managers.get(RoomCode.HARDWARE), "{\"score\":4}"));
    expectOk(post("/rooms/" + rooms.get(RoomCode.PROBLEM_SOLVING) + "/hints", admin, "{\"teamId\":" + bravo.id() + "}"));
    for (RoomCode code : RoomCode.values()) {
      db.update("INSERT INTO collected_clue(team_id, room_id, source, collected_at) VALUES (?, ?, 'MANUAL', now())", alpha.id(), rooms.get(code));
    }
    setRound(5);
    openFinal();
    playTo(alpha, 2);

    String reason = "{\"reason\":\"Resetting one team for the test\"}";
    Response reset = expectOk(post("/admin/reset/team/" + alpha.id(), admin, reason));
    long snapshot = ((Number) reset.json("$.snapshotId")).longValue();
    for (String table : List.of("score_deduction", "room_manual_score", "team_final", "final_clue_attempt", "final_unlock_attempt")) {
      assertEquals(0, count("SELECT count(*) FROM " + table + " WHERE team_id = " + alpha.id()), table);
    }
    assertEquals(1, count("SELECT count(*) FROM score_deduction WHERE team_id = " + bravo.id()), "the other team keeps its deductions");
    assertEquals(1, count("SELECT count(*) FROM room_manual_score WHERE team_id = " + bravo.id()));
    assertEquals(2, snapshotRows(snapshot, "score_deduction"));
    assertEquals(1, snapshotRows(snapshot, "room_manual_score"));
    assertEquals(1, snapshotRows(snapshot, "team_final"));
    assertEquals(2, snapshotRows(snapshot, "final_clue_attempt"));
    assertEquals(1, snapshotRows(snapshot, "final_unlock_attempt"));

    String eventReset = "{\"confirmText\":\"RESET\",\"password\":\"%s\",\"reason\":\"Resetting the event for the test\"}".formatted(PASSWORD);
    long eventSnapshot = ((Number) expectOk(post("/admin/reset/event", admin, eventReset)).json("$.snapshotId")).longValue();
    for (String table : List.of("score_deduction", "room_manual_score", "team_final", "final_clue_attempt", "final_unlock_attempt", "final_time_adjustment")) {
      assertEquals(0, count("SELECT count(*) FROM " + table), table);
    }
    assertEquals(1, snapshotRows(eventSnapshot, "score_deduction"));
    assertEquals(1, snapshotRows(eventSnapshot, "room_manual_score"));
    assertEquals(1, count("SELECT count(*) FROM final_content"), "the content file is setup and stays");
    assertNull(db.queryForObject("SELECT final_phase FROM event_state", String.class));
    assertEquals(false, expectOk(get("/admin/board", admin)).json("$.state.finalOpen"));
    assertThrowsOnDelete();
  }

  private void assertThrowsOnDelete() {
    boolean refused = false;
    try {
      db.update(
        "INSERT INTO final_clue_attempt(id, team_id, clue, value, correct, received_at, idempotency_key, request_hash) VALUES (?, ?, 1, 'x', FALSE, now(), ?, 'x')",
        UUID.randomUUID(),
        alpha.id(),
        UUID.randomUUID()
      );
      db.update("DELETE FROM final_clue_attempt");
    } catch (DataAccessException e) {
      refused = true;
    }
    assertTrue(refused, "the final's records are append-only outside a reset");
    assertFalse(count("SELECT count(*) FROM final_clue_attempt") == 0);
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private int snapshotRows(long snapshot, String table) {
    return db.queryForObject("SELECT jsonb_array_length(data -> ?) FROM event_snapshot WHERE id = ?", Integer.class, table, snapshot);
  }

  private int count(String sql) {
    return db.queryForObject(sql, Integer.class);
  }

  /** Ten saved stages of the AI mission worth {@code total} points together (stage points sum to the room total). */
  private void aiStages(TestTeam team, int total) {
    BigDecimal each = new BigDecimal(total).divide(BigDecimal.TEN, 2, java.math.RoundingMode.HALF_UP);
    for (int stage = 1; stage <= 10; stage++) {
      db.update(
        "INSERT INTO sentinel_submission(team_id, room_id, stage, round, answer, points_earned, submitted_at, idempotency_key, request_hash) VALUES (?, ?, ?, 1, '{}'::jsonb, ?, now(), ?, 'x')",
        team.id(),
        ai,
        stage,
        each,
        UUID.randomUUID()
      );
    }
  }

  private BigDecimal roomPart(String teamName, int roomIndex, String part) {
    List<Number> found = expectOk(get("/admin/scores", admin)).json("$[?(@.teamName == '" + teamName + "')].rooms[" + roomIndex + "]." + part);
    return new BigDecimal(found.get(0).toString()).setScale(2);
  }

  private String roomSource(String teamName, int roomIndex) {
    List<String> found = expectOk(get("/admin/scores", admin)).json("$[?(@.teamName == '" + teamName + "')].rooms[" + roomIndex + "].source");
    return found.get(0);
  }

  private BigDecimal total(String teamName) {
    List<Number> found = expectOk(get("/admin/scores", admin)).json("$[?(@.teamName == '" + teamName + "')].total");
    return new BigDecimal(found.get(0).toString()).setScale(2);
  }
}
