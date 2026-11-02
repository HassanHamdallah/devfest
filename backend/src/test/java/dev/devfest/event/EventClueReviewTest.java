package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runtime clue keys: the empty-key guards, re-checks when a key changes, the manager's per-submission
 * override, and what teams may see of it.
 */
class EventClueReviewTest extends EventIntegrationTest {

  private TestTeam alpha;
  private TestTeam bravo;

  @BeforeEach
  void prepare() {
    loadContent();
    alpha = team("Alpha", GroupCode.A);
    bravo = team("Bravo", GroupCode.A);
    setRound(1);
  }

  @Test
  void aRoomNeedsAClueKeyToOpenAndToAcceptAnswers() {
    expectOk(setClueKey(RoomCode.AI, "   "));
    expectError(post("/admin/rooms/" + rooms.get(RoomCode.AI) + "/open", admin, null), 409, "Set the clue key for this room before opening it.");
    List<String> alerts = expectOk(get("/admin/board", admin)).json("$.alerts[*].code");
    assertTrue(alerts.contains("MISSING_CLUE_KEY"), alerts.toString());
    assertEquals(false, expectOk(get("/rooms/" + rooms.get(RoomCode.AI) + "/board", managers.get(RoomCode.AI))).json("$.room.clueKeySet"));

    expectOk(setClueKey(RoomCode.AI, "WHITE VAN"));
    openRoom(RoomCode.AI);
    expectError(setClueKey(RoomCode.AI, ""), 409, "Close the room before removing its clue key.");
    // Only reachable by editing the database directly: the submission endpoint still refuses.
    db.update("UPDATE event_room SET clue_key = '' WHERE id = ?", rooms.get(RoomCode.AI));
    expectError(submit(alpha, RoomCode.AI, UUID.randomUUID(), "o", "WHITE VAN"), 409, "This room is not ready yet. Ask the organizer.");
  }

  @Test
  void fixingATypoInTheKeyReChecksEarlierAnswers() {
    expectOk(setClueKey(RoomCode.AI, "WHITE VN"));
    openRoom(RoomCode.AI);
    Response receipt = expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), "o", "white van"));
    assertEquals(false, receipt.json("$.clueCorrect"));
    assertEquals(0, clues(alpha));

    long revision = revision();
    Response saved = expectOk(setClueKey(RoomCode.AI, "WHITE VAN"));
    assertEquals(1, (int) saved.json("$.recheckedSubmissions"));
    assertEquals(revision + 1, revision());
    assertEquals(1, clues(alpha));
    Response board = expectOk(get("/team/board", alpha.token()));
    assertEquals(true, board.json("$.rooms[0].lastReceipt.clueCorrect"));
    assertEquals(true, board.json("$.rooms[0].lastReceipt.updatedAfterReview"));
    assertEquals(false, db.queryForObject("SELECT clue_correct FROM room_submission", Boolean.class), "history is kept");
    String target = db.queryForObject("SELECT target FROM audit_event WHERE action = 'EVENT_CLUE_KEY_CHANGED' ORDER BY id DESC LIMIT 1", String.class);
    assertEquals("room:" + rooms.get(RoomCode.AI) + " rechecked:1", target);
    String audited = db.queryForObject("SELECT string_agg(concat_ws('|', action, target), ',') FROM audit_event", String.class);
    assertFalse(audited.toUpperCase().contains("WHITE"), "the key is never audited: " + audited);
  }

  @Test
  void aKeyChangeCanRemoveARoomClueButNeverARecoveryClue() {
    openRoom(RoomCode.AI);
    expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), "o", "WHITE VAN"));
    expectOk(submit(bravo, RoomCode.AI, UUID.randomUUID(), "o", "WHITE VAN"));
    // Bravo's clue for this room came from the recovery challenge instead.
    db.update("DELETE FROM collected_clue WHERE team_id = ?", bravo.id());
    db.update(
      "INSERT INTO collected_clue(team_id, room_id, source, collected_at) VALUES (?, ?, 'RECOVERY', now())",
      bravo.id(),
      rooms.get(RoomCode.AI)
    );
    closeRoom(RoomCode.AI);

    assertEquals(2, (int) expectOk(setClueKey(RoomCode.AI, "BLACK VAN")).json("$.recheckedSubmissions"));
    assertEquals(0, clues(alpha));
    assertEquals("RECOVERY", db.queryForObject("SELECT source FROM collected_clue WHERE team_id = ?", String.class, bravo.id()));

    expectOk(setClueKey(RoomCode.AI, "WHITE VAN"));
    assertEquals(1, clues(alpha));
    assertEquals("RECOVERY", db.queryForObject("SELECT source FROM collected_clue WHERE team_id = ?", String.class, bravo.id()));
  }

  @Test
  void anOverrideWinsOverTheKeyUntilItIsSetBackToAutomatic() {
    openRoom(RoomCode.AI);
    String nearMiss = expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), "o", "WHITE VANN")).json("$.id");
    assertEquals(0, clues(alpha));

    Response accepted = expectOk(override(nearMiss, "ACCEPT", 1));
    assertEquals(true, accepted.json("$.effectiveCorrect"));
    assertEquals(false, accepted.json("$.autoCorrect"));
    assertEquals(2, (int) accepted.json("$.version"));
    assertTrue(((String) accepted.json("$.overrideByName")).startsWith("MANAGER"));
    assertEquals(1, clues(alpha));
    long revision = revision();
    expectOk(override(nearMiss, "ACCEPT", 1));
    assertEquals(revision, revision(), "an identical retry changes nothing");
    expectError(override(nearMiss, "REJECT", 1), 409, "This clue verdict was changed by someone else. Reload and try again.");

    closeRoom(RoomCode.AI);
    expectOk(setClueKey(RoomCode.AI, "RED VAN"));
    assertEquals(1, clues(alpha), "the override survives a key change");
    Response automatic = expectOk(override(nearMiss, "AUTOMATIC", 2));
    assertEquals(false, automatic.json("$.effectiveCorrect"));
    assertNull(automatic.json("$.overrideByName"));
    assertEquals(0, clues(alpha));

    openRoom(RoomCode.AI);
    String correct = expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), "o", "red van")).json("$.id");
    assertEquals(1, clues(alpha));
    expectOk(override(correct, "REJECT", 1));
    assertEquals(0, clues(alpha));

    assertEquals(2, history(nearMiss));
    assertEquals(1, history(correct));
    assertEquals(3, (int) db.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'EVENT_CLUE_OVERRIDE'", Integer.class));
    assertEquals(
      List.of("ACCEPT", "AUTOMATIC"),
      db.queryForList(
        "SELECT coalesce(new_override, 'AUTOMATIC') FROM submission_clue_verdict_history WHERE submission_id = ?::uuid ORDER BY version",
        String.class,
        nearMiss
      )
    );

    setRound(5);
    openFinal();
    expectError(override(correct, "AUTOMATIC", 2), 409, "Clue verdicts cannot change after the final opens.");
  }

  @Test
  void onlyTheRoomsManagerOrAdminMayOverride() {
    openRoom(RoomCode.AI);
    String id = expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), "o", "x")).json("$.id");
    String path = "/rooms/" + rooms.get(RoomCode.AI) + "/submissions/" + id + "/clue-override";
    String body = "{\"override\":\"ACCEPT\",\"version\":1}";
    expectError(put(path, managers.get(RoomCode.LINUX), body), 404, "Room not found.");
    assertEquals(403, put(path, alpha.token(), body).status());
    String otherRoom = "/rooms/" + rooms.get(RoomCode.LINUX) + "/submissions/" + id + "/clue-override";
    expectError(put(otherRoom, admin, body), 404, "Submission not found.");
    expectOk(put(path, admin, body));
  }

  @Test
  void managersSeeTheVerdictDetailsAndTeamsOnlyTheEffectiveResult() {
    openRoom(RoomCode.AI);
    String id = expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), "o", "WHITE VANN")).json("$.id");
    expectOk(override(id, "ACCEPT", 1));

    Response room = expectOk(get("/rooms/" + rooms.get(RoomCode.AI) + "/board", managers.get(RoomCode.AI)));
    assertEquals(true, room.json("$.room.clueKeySet"));
    assertEquals(List.of(false), room.json("$.teams[?(@.teamName == 'Alpha')].latestSubmission.autoCorrect"));
    assertEquals(List.of("ACCEPT"), room.json("$.teams[?(@.teamName == 'Alpha')].latestSubmission.override"));
    assertEquals(List.of(true), room.json("$.teams[?(@.teamName == 'Alpha')].latestSubmission.effectiveCorrect"));
    assertEquals(List.of(2), room.json("$.teams[?(@.teamName == 'Alpha')].latestSubmission.verdictVersion"));

    for (String path : List.of("/team/board", "/team/submissions", "/team/submissions/" + id, "/team/final", "/home")) {
      String body = expectOk(get(path, alpha.token())).body();
      for (String hidden : List.of("autoCorrect", "override", "WHITE VAN", "clueKey", "verdict")) {
        assertFalse(body.contains(hidden), hidden + " leaked from " + path + ": " + body);
      }
    }
    assertEquals(true, expectOk(get("/team/submissions/" + id, alpha.token())).json("$.clueCorrect"));
  }

  private Response override(String submissionId, String verdict, long version) {
    String path = "/rooms/" + rooms.get(RoomCode.AI) + "/submissions/" + submissionId + "/clue-override";
    return put(path, managers.get(RoomCode.AI), "{\"override\":\"%s\",\"version\":%d}".formatted(verdict, version));
  }

  private int clues(TestTeam team) {
    return db.queryForObject("SELECT count(*) FROM collected_clue WHERE team_id = ?", Integer.class, team.id());
  }

  private int history(String submissionId) {
    return db.queryForObject(
      "SELECT count(*) FROM submission_clue_verdict_history WHERE submission_id = ?::uuid",
      Integer.class,
      submissionId
    );
  }
}
