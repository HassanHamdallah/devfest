package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.support.TransactionTemplate;

/** The admin overrides: swaps and moves, room exceptions, manual clues, disqualification, resets, snapshots. */
class EventOverrideTest extends EventIntegrationTest {

  private static final String REASON = "\"reason\":\"Projector broke in the AI room\"";

  @Autowired
  private TransactionTemplate transactions;

  private TestTeam alpha;
  private TestTeam bravo;
  private TestTeam charlie;
  private TestTeam delta;

  @BeforeEach
  void prepare() {
    loadContent();
    alpha = team("Alpha", GroupCode.A);
    bravo = team("Bravo", GroupCode.B);
    charlie = team("Charlie", GroupCode.C);
    delta = team("Delta", GroupCode.D);
  }

  private record Endpoint(HttpMethod method, String path, String body, boolean needsReason) {}

  @Test
  void everyOverrideIsAdminOnlyAndNeedsAReason() {
    String teams = "\"teamA\":%d,\"teamB\":%d".formatted(alpha.id(), bravo.id());
    String teamRoom = "\"teamId\":%d,\"roomId\":%d".formatted(alpha.id(), rooms.get(RoomCode.LINUX));
    List<Endpoint> endpoints = List.of(
      new Endpoint(HttpMethod.POST, "/admin/overrides/swap-preview", "{" + teams + "}", false),
      new Endpoint(HttpMethod.POST, "/admin/overrides/swap", "{" + teams + "%s}", true),
      new Endpoint(HttpMethod.POST, "/admin/overrides/move-preview", "{\"teamId\":" + alpha.id() + ",\"group\":\"B\"}", false),
      new Endpoint(HttpMethod.PUT, "/admin/teams/" + alpha.id() + "/group", "{\"group\":\"B\"%s}", true),
      new Endpoint(HttpMethod.POST, "/admin/overrides/room-exception/preview", "{" + teamRoom + "}", false),
      new Endpoint(HttpMethod.PUT, "/admin/overrides/room-exception", "{" + teamRoom + "%s}", true),
      new Endpoint(HttpMethod.DELETE, "/admin/overrides/room-exception/" + alpha.id(), "{%s}", true),
      new Endpoint(HttpMethod.POST, "/admin/overrides/clue", "{" + teamRoom + ",\"action\":\"GRANT\"%s}", true),
      new Endpoint(HttpMethod.POST, "/admin/teams/" + alpha.id() + "/disqualify", "{%s}", true),
      new Endpoint(HttpMethod.POST, "/admin/teams/" + alpha.id() + "/reinstate", "{%s}", true),
      new Endpoint(HttpMethod.POST, "/admin/reset/final", "{%s}", true),
      new Endpoint(HttpMethod.POST, "/admin/reset/team/" + alpha.id(), "{%s}", true),
      new Endpoint(HttpMethod.POST, "/admin/reset/event", "{\"confirmText\":\"RESET\",\"password\":\"x\"%s}", true),
      new Endpoint(HttpMethod.POST, "/admin/snapshots", "{%s}", true),
      new Endpoint(HttpMethod.GET, "/admin/snapshots", null, false),
      new Endpoint(HttpMethod.GET, "/admin/snapshots/1/download", null, false)
    );
    for (Endpoint endpoint : endpoints) {
      String withReason = endpoint.body() == null ? null : endpoint.body().formatted(endpoint.needsReason() ? sep(endpoint) + REASON : "");
      assertEquals(403, call(endpoint.method(), endpoint.path(), alpha.token(), withReason, null).status(), "member " + endpoint.path());
      assertEquals(403, call(endpoint.method(), endpoint.path(), managers.get(RoomCode.AI), withReason, null).status(), "manager " + endpoint.path());
      assertEquals(401, call(endpoint.method(), endpoint.path(), null, withReason, null).status(), "anonymous " + endpoint.path());
      if (endpoint.needsReason()) {
        assertEquals(400, call(endpoint.method(), endpoint.path(), admin, endpoint.body().formatted(""), null).status(), "no reason " + endpoint.path());
      }
    }
  }

  @Test
  void aSwapExchangesGroupsAndKeepsCapacity() {
    Response done = expectOk(post("/admin/overrides/swap", admin, swap(alpha, bravo)));
    assertEquals("Alpha moved to group B and Bravo to group A.", done.json("$.message"));
    assertEquals("B", group(alpha));
    assertEquals("A", group(bravo));
    assertEquals(List.of(1, 1, 1, 1), expectOk(get("/admin/organization", admin)).json("$.groups[*].activeTeams"));
    assertEquals("Projector broke in the AI room", db.queryForObject("SELECT reason FROM audit_event WHERE action = 'EVENT_TEAMS_SWAPPED'", String.class));
    List<String> recent = expectOk(get("/admin/board", admin)).json("$.recentOverrides[*].action");
    assertEquals(List.of("EVENT_TEAMS_SWAPPED"), recent);
  }

  @Test
  void aMidEventSwapPreviewWarnsAboutRooms() {
    setRound(2);
    Response preview = expectOk(post("/admin/overrides/swap-preview", admin, "{\"teamA\":%d,\"teamB\":%d}".formatted(bravo.id(), alpha.id())));
    assertEquals(List.of("Alpha will visit the AI room twice.", "Alpha will miss the Linux room."), preview.json("$[1].warnings"));
    assertEquals(List.of("AI", "PROBLEM_SOLVING"), preview.json("$[1].visited"));
    assertEquals(List.of("HARDWARE", "AI"), preview.json("$[1].remaining[*].room"));
  }

  @Test
  void movingAfterRoundOneNeedsTheConfirmationAndRoomInTheGroup() {
    setRound(1);
    String body = "{\"group\":\"B\"," + REASON + "}";
    expectError(put("/admin/teams/" + alpha.id() + "/group", admin, body), 409,
      "Groups are locked after round 1 starts. Review the preview and confirm the impact to move the team.");
    expectOk(put("/admin/teams/" + alpha.id() + "/group", admin, "{\"group\":\"B\",\"confirmImpact\":true," + REASON + "}"));
    assertEquals("B", group(alpha));

    expectOk(put("/admin/teams/planned-count", admin, "{\"plannedCount\":5}"));
    expectError(put("/admin/teams/" + charlie.id() + "/group", admin, "{\"group\":\"B\",\"confirmImpact\":true," + REASON + "}"), 409,
      "Group B already has 2 active teams. Swap the team with one in that group instead.");
  }

  @Test
  void aRoomExceptionSendsATeamElsewhereForTheCurrentRoundOnly() {
    setRound(1);
    openRoom(RoomCode.AI);
    openRoom(RoomCode.LINUX);
    String exception = "{\"teamId\":%d,\"roomId\":%d,%s}".formatted(alpha.id(), rooms.get(RoomCode.LINUX), REASON);
    Response applied = expectOk(put("/admin/overrides/room-exception", admin, exception));
    assertEquals("This round Alpha goes to the Linux room.", applied.json("$.message"));

    expectOk(submit(alpha, RoomCode.LINUX, UUID.randomUUID(), "o", "x"));
    expectError(submit(alpha, RoomCode.AI, UUID.randomUUID(), "o", "x"), 409, "This is not your team's room in the current round.");
    Response teamBoard = expectOk(get("/team/board", alpha.token()));
    assertEquals("LINUX", teamBoard.json("$.scheduledRoom.code"));
    assertEquals(true, teamBoard.json("$.scheduledRoom.exception"));
    List<String> linux = expectOk(get("/rooms/" + rooms.get(RoomCode.LINUX) + "/board", managers.get(RoomCode.LINUX))).json("$.teamsExpectedNow");
    assertEquals(List.of("Alpha", "Charlie"), linux);
    List<String> ai = expectOk(get("/rooms/" + rooms.get(RoomCode.AI) + "/board", managers.get(RoomCode.AI))).json("$.teamsExpectedNow");
    assertEquals(List.of(), ai);
    assertEquals(List.of("LINUX"), expectOk(get("/admin/organization", admin)).json("$.teams[?(@.name == 'Alpha')].exceptionRoom"));

    setRound(2);
    openRoom(RoomCode.PROBLEM_SOLVING);
    expectError(submit(alpha, RoomCode.LINUX, UUID.randomUUID(), "o", "x"), 409, "This is not your team's room in the current round.");
    expectOk(submit(alpha, RoomCode.PROBLEM_SOLVING, UUID.randomUUID(), "o", "x"));
  }

  @Test
  void manualCluesChangeTheFinalStageAndKeepRecoveryCluesUnlessAsked() {
    collectClues(alpha, RoomCode.AI, RoomCode.PROBLEM_SOLVING, RoomCode.LINUX);
    setRound(5);
    openFinal();
    assertEquals("RECOVERY", expectOk(get("/team/final", alpha.token())).json("$.status"));
    expectOk(post("/admin/overrides/clue", admin, clue(alpha, RoomCode.HARDWARE, "GRANT", false)));
    assertEquals("IN_FINAL", expectOk(get("/team/final", alpha.token())).json("$.status"));
    assertEquals("MANUAL", source(alpha, RoomCode.HARDWARE));

    db.update("UPDATE collected_clue SET source = 'RECOVERY' WHERE team_id = ? AND room_id = ?", alpha.id(), rooms.get(RoomCode.HARDWARE));
    expectError(post("/admin/overrides/clue", admin, clue(alpha, RoomCode.HARDWARE, "REVOKE", false)), 409,
      "This clue was won in the recovery challenge. Tick \"also remove recovery clue\" to remove it.");
    assertEquals("RECOVERY", source(alpha, RoomCode.HARDWARE));
    expectOk(post("/admin/overrides/clue", admin, clue(alpha, RoomCode.AI, "REVOKE", false)));
    assertNull(source(alpha, RoomCode.AI));
    expectOk(post("/admin/overrides/clue", admin, clue(alpha, RoomCode.HARDWARE, "REVOKE", true)));
    assertNull(source(alpha, RoomCode.HARDWARE));
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM room_submission WHERE team_id = ? AND room_id = ?", Integer.class, alpha.id(), rooms.get(RoomCode.AI)));
  }

  @Test
  void aDisqualifiedTeamCannotSubmitAndIsRankedLast() {
    expectOk(post("/admin/teams/" + alpha.id() + "/disqualify", admin, "{" + REASON + "}"));
    setRound(1);
    openRoom(RoomCode.AI);
    String disqualified = "Your team has been disqualified. Contact the organizer.";
    expectError(submit(alpha, RoomCode.AI, UUID.randomUUID(), "o", "x"), 409, disqualified);
    expectError(suspect(alpha, CORRECT_SUSPECT), 409, disqualified);
    expectError(unlockWithKeys(alpha), 409, disqualified);
    Response board = expectOk(get("/admin/board", admin));
    assertEquals("Alpha", board.json("$.ranking[3].scores.teamName"));
    assertEquals(true, board.json("$.ranking[3].scores.disqualified"));
    assertNull(board.json("$.ranking[3].rank"));
    assertEquals(true, expectOk(get("/team/board", alpha.token())).json("$.disqualified"));

    expectOk(post("/admin/teams/" + alpha.id() + "/reinstate", admin, "{" + REASON + "}"));
    expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), "o", "x"));
  }

  @Test
  void resettingTheFinalAllowsOpeningItAgainAndKeepsRoomData() {
    collectClues(alpha, RoomCode.values());
    String ai = "/rooms/" + rooms.get(RoomCode.AI);
    expectOk(post(ai + "/hints", admin, "{\"teamId\":" + alpha.id() + "}"));
    setRound(5);
    openFinal();
    playTo(alpha, 4);
    openInvestigationPhase();
    expectOk(suspect(alpha, WRONG_SUSPECT));
    int submissions = count("room_submission");

    Response reset = expectOk(post("/admin/reset/final", admin, "{" + REASON + "}"));
    long snapshot = ((Number) reset.json("$.snapshotId")).longValue();
    assertEquals(0, count("team_final") + count("final_clue_attempt") + count("final_unlock_attempt"));
    assertEquals(0, count("final_time_adjustment"));
    assertEquals(submissions, count("room_submission"));
    assertEquals(4, count("collected_clue"));
    assertEquals(1, count("score_deduction"), "deductions belong to the rooms, not to the final");
    assertEquals(1, snapshotRows(snapshot, "team_final"));
    assertEquals(4, snapshotRows(snapshot, "final_clue_attempt"));
    assertEquals(1, snapshotRows(snapshot, "final_unlock_attempt"));
    assertNull(db.queryForObject("SELECT final_phase FROM event_state", String.class), "the final timer is cleared with the final");

    openFinal();
    assertEquals("IN_FINAL", expectOk(get("/team/final", alpha.token())).json("$.status"));
    assertEquals("UNLOCK", expectOk(get("/team/final", alpha.token())).json("$.screen"));
  }

  @Test
  void resettingOneTeamClearsOnlyItsProgressAndSignsItOut() {
    setRound(1);
    openRoom(RoomCode.AI);
    openRoom(RoomCode.PROBLEM_SOLVING);
    expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), "o", CLUE_KEYS.get(RoomCode.AI)));
    expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), "o", "wrong"));
    expectOk(submit(bravo, RoomCode.PROBLEM_SOLVING, UUID.randomUUID(), "o", CLUE_KEYS.get(RoomCode.PROBLEM_SOLVING)));

    Response reset = expectOk(post("/admin/reset/team/" + alpha.id(), admin, "{" + REASON + "}"));
    long snapshot = ((Number) reset.json("$.snapshotId")).longValue();
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM room_submission WHERE team_id = ?", Integer.class, alpha.id()));
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM collected_clue WHERE team_id = ?", Integer.class, alpha.id()));
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM room_submission WHERE team_id = ?", Integer.class, bravo.id()));
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM collected_clue WHERE team_id = ?", Integer.class, bravo.id()));
    expectSessionEnded(get("/team/board", alpha.token()), "ORGANIZER");
    assertEquals("A", group(alpha));
    assertEquals(2, snapshotRows(snapshot, "room_submission"));
    assertEquals(2, snapshotRows(snapshot, "submission_clue_verdict"));
    assertEquals("RESET_TEAM", db.queryForObject("SELECT kind FROM event_snapshot WHERE id = ?", String.class, snapshot));
  }

  @Test
  void resettingTheEventNeedsResetAndThePasswordAndKeepsSetup() {
    collectClues(alpha, RoomCode.AI);
    int submissions = count("room_submission");
    int accounts = count("app_user");
    String body = "{\"confirmText\":\"%s\",\"password\":\"%s\"," + REASON + "}";

    expectError(post("/admin/reset/event", admin, body.formatted("RESET", "wrong-password-123")), 403, "The password is incorrect.");
    expectError(post("/admin/reset/event", admin, body.formatted("reset", PASSWORD)), 400, "Type RESET to confirm.");
    assertEquals(submissions, count("room_submission"));
    assertEquals(0, count("event_snapshot"));

    Response reset = expectOk(post("/admin/reset/event", admin, body.formatted("RESET", PASSWORD)));
    long snapshot = ((Number) reset.json("$.snapshotId")).longValue();
    Response state = expectOk(get("/admin/board", admin));
    assertEquals(0, (int) state.json("$.state.currentRound"));
    assertNull(state.json("$.state.phase"));
    assertEquals(List.of(false, false, false, false), state.json("$.rooms[*].open"));
    for (String table : List.of("room_submission", "collected_clue", "submission_clue_verdict", "score_deduction", "room_manual_score", "team_final", "final_clue_attempt", "room_arrival")) {
      assertEquals(0, count(table), table);
    }
    assertEquals(accounts, count("app_user"));
    assertEquals(4, count("event_team"));
    assertEquals(1, count("final_content"), "the final content is setup, not progress");
    assertEquals(CLUE_KEYS.get(RoomCode.AI), db.queryForObject("SELECT clue_key FROM event_room WHERE id = ?", String.class, rooms.get(RoomCode.AI)));
    assertEquals(submissions, snapshotRows(snapshot, "room_submission"));

    Response download = call(HttpMethod.GET, "/admin/snapshots/" + snapshot + "/download", admin, null, null);
    assertEquals(200, download.status());
    assertEquals("RESET_EVENT", download.json("$.kind"));
    assertEquals(submissions, (int) download.json("$.data.room_submission.length()"));
    assertEquals(List.of("RESET_EVENT"), expectOk(get("/admin/snapshots", admin)).json("$[*].kind"));
  }

  @Test
  void appendOnlyRowsCanBeDeletedOnlyByAResetTransaction() {
    setRound(1);
    openRoom(RoomCode.AI);
    expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), "o", "x"));
    String hint = "{\"teamId\":%d,\"note\":\"\"}".formatted(alpha.id());
    expectOk(post("/rooms/" + rooms.get(RoomCode.AI) + "/hints", managers.get(RoomCode.AI), hint));
    assertThrows(DataAccessException.class, () -> db.update("DELETE FROM room_submission"));
    assertThrows(DataAccessException.class, () ->
      transactions.executeWithoutResult(status -> {
        db.update("DELETE FROM submission_clue_verdict");
        db.update("DELETE FROM room_submission");
      })
    );
    assertEquals(1, count("room_submission"));
    assertThrows(DataAccessException.class, () ->
      transactions.executeWithoutResult(status -> {
        db.queryForObject("SELECT set_config('devfest.allow_reset', 'on', true)", String.class);
        db.update("UPDATE room_submission SET clue_answer = 'changed'");
      })
    );
    transactions.executeWithoutResult(status -> {
      db.queryForObject("SELECT set_config('devfest.allow_reset', 'on', true)", String.class);
      db.update("DELETE FROM submission_clue_verdict");
      db.update("DELETE FROM room_submission");
      assertEquals(0, count("room_submission"));
      status.setRollbackOnly();
    });
    assertEquals(1, count("room_submission"));
    assertThrows(DataAccessException.class, () -> db.update("DELETE FROM score_deduction"));
  }

  private static String sep(Endpoint endpoint) {
    return endpoint.body().startsWith("{%s") ? "" : ",";
  }

  private static String swap(TestTeam a, TestTeam b) {
    return "{\"teamA\":%d,\"teamB\":%d,%s}".formatted(a.id(), b.id(), REASON);
  }

  private String clue(TestTeam team, RoomCode room, String action, boolean alsoRecovery) {
    return "{\"teamId\":%d,\"roomId\":%d,\"action\":\"%s\",\"alsoRemoveRecovery\":%s,%s}".formatted(
      team.id(),
      rooms.get(room),
      action,
      alsoRecovery,
      REASON
    );
  }

  private String group(TestTeam team) {
    return db.queryForObject("SELECT group_code FROM event_team WHERE id = ?", String.class, team.id());
  }

  private String source(TestTeam team, RoomCode room) {
    List<String> sources = db.queryForList(
      "SELECT source FROM collected_clue WHERE team_id = ? AND room_id = ?",
      String.class,
      team.id(),
      rooms.get(room)
    );
    return sources.isEmpty() ? null : sources.getFirst();
  }

  private int count(String table) {
    return db.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private int snapshotRows(long snapshotId, String table) {
    return db.queryForObject("SELECT jsonb_array_length(data -> ?) FROM event_snapshot WHERE id = ?", Integer.class, table, snapshotId);
  }
}
