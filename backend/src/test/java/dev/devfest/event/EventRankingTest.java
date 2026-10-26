package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ranking (D12) with controlled timestamps written straight into devfest_test: adjusted time, tie-breaks,
 * shared ranks, unfinished teams and publication.
 */
class EventRankingTest extends EventIntegrationTest {

  private static final Instant FINAL_STARTED = Instant.parse("2026-11-01T10:00:00Z");

  private long adminId;

  @BeforeEach
  void startFinal() {
    loadContent();
    adminId = db.queryForObject("SELECT id FROM app_user WHERE role = 'ADMIN'", Long.class);
    db.update(
      "UPDATE event_state SET current_round = 5, final_open = TRUE, final_started_at = ? WHERE id = 1",
      Timestamp.from(FINAL_STARTED)
    );
  }

  @Test
  void finishedTeamsAreOrderedByAdjustedTimeThenPenaltyEntriesThenFinishTime() {
    TestTeam fast = team("Fast", GroupCode.A);
    TestTeam penalized = team("Penalized", GroupCode.A);
    TestTeam early = team("Early", GroupCode.B);
    TestTeam tieOne = team("Tie one", GroupCode.B);
    TestTeam tieTwo = team("Tie two", GroupCode.C);
    TestTeam last = team("Last", GroupCode.C);
    finish(fast, 600);
    penalty(fast, 5, true);
    finish(penalized, 480);
    penalty(penalized, 2, false);
    finish(early, 300);
    penalty(early, 5, false);
    finish(tieOne, 900);
    finish(tieTwo, 900);
    finish(last, 1000);
    team("Unfinished two clues", GroupCode.D);
    TestTeam threeClues = team("Unfinished three clues", GroupCode.D);
    TestTeam twoClues = team("Another two clues", GroupCode.D);
    clues(threeClues, 3);
    clues(twoClues, 2);

    Response board = expectOk(get("/admin/board", admin));
    List<String> names = board.json("$.ranking[*].teamName");
    assertEquals(
      List.of("Fast", "Early", "Penalized", "Tie one", "Tie two", "Last", "Unfinished three clues", "Another two clues", "Unfinished two clues"),
      names
    );
    List<Integer> ranks = board.json("$.ranking[*].rank");
    assertEquals(Arrays.asList(1, 2, 3, 4, 4, 6, null, null, null), ranks);
    List<Integer> adjusted = board.json("$.ranking[0:3].adjustedSeconds");
    assertEquals(List.of(600, 600, 600), adjusted);
    assertEquals(0, (int) board.json("$.ranking[0].penaltyMinutes"));
  }

  @Test
  void aTimeAdjustmentChangesAdjustedTimeButNotThePenaltyCount() {
    TestTeam team = team("Compensated", GroupCode.A);
    finish(team, 1000);
    penalty(team, 5, false);
    String body = "{\"teamId\":%d,\"minutes\":-5,\"reason\":\"Wi-Fi was down for five minutes\"}".formatted(team.id());
    expectOk(post("/admin/overrides/time-adjustment", admin, body));
    Response board = expectOk(get("/admin/board", admin));
    assertEquals(1000, (int) board.json("$.ranking[0].adjustedSeconds"), "+5 penalty and -5 adjustment cancel out");
    assertEquals(1, (int) board.json("$.ranking[0].penaltyEntries"), "the adjustment is not a penalty entry");
    assertEquals(0, (int) board.json("$.ranking[0].penaltyMinutes"));

    long adjustment = db.queryForObject("SELECT id FROM penalty WHERE kind = 'TIME_ADJUSTMENT'", Long.class);
    expectOk(post("/penalties/" + adjustment + "/void", admin, "{\"reason\":\"Entered for the wrong team\"}"));
    assertEquals(1300, (int) expectOk(get("/admin/board", admin)).json("$.ranking[0].adjustedSeconds"));
  }

  @Test
  void aDisqualifiedTeamIsListedLastWithoutARank() {
    TestTeam fastest = team("Fastest", GroupCode.A);
    TestTeam second = team("Second", GroupCode.B);
    finish(fastest, 500);
    finish(second, 900);
    expectOk(post("/admin/teams/" + fastest.id() + "/disqualify", admin, "{\"reason\":\"Shared answers with another team\"}"));
    Response board = expectOk(get("/admin/board", admin));
    assertEquals(List.of("Second", "Fastest"), board.json("$.ranking[*].teamName"));
    assertEquals(1, (int) board.json("$.ranking[0].rank"));
    assertNull(board.json("$.ranking[1].rank"));
    assertEquals(true, board.json("$.ranking[1].disqualified"));
    expectOk(put("/admin/publication", admin, "{\"published\":true}"));
    assertEquals(List.of(false, true), expectOk(get("/results", second.token())).json("$.rows[*].disqualified"));
  }

  @Test
  void theAdminRankingNamesTheRoomsWhoseCluesATeamHolds() {
    TestTeam collector = team("Collector", GroupCode.A);
    TestTeam empty = team("Nothing yet", GroupCode.B);
    // Collected out of room order on purpose: the list follows the room positions.
    clue(collector, RoomCode.HARDWARE);
    clue(collector, RoomCode.AI);
    clue(collector, RoomCode.LINUX);

    Response board = expectOk(get("/admin/board", admin));
    assertEquals(List.of("Collector", "Nothing yet"), board.json("$.ranking[*].teamName"));
    assertEquals(List.of("AI", "LINUX", "HARDWARE"), board.json("$.ranking[0].clueRooms"));
    assertEquals(3, (int) board.json("$.ranking[0].clues"));
    assertEquals(List.of(), board.json("$.ranking[1].clueRooms"));

    expectOk(put("/admin/publication", admin, "{\"published\":true}"));
    assertFalse(expectOk(get("/results", empty.token())).body().contains("clueRooms"), "the public ranking keeps only the count");
  }

  @Test
  void resultsArePrivateUntilPublished() {
    TestTeam member = team("Member team", GroupCode.A);
    finish(member, 120);
    expectError(get("/results", member.token()), 403, "Results are not published yet.");
    expectOk(get("/results", admin));
    expectOk(put("/admin/publication", admin, "{\"published\":true}"));
    Response results = expectOk(get("/results", member.token()));
    assertEquals("Member team", results.json("$.rows[0].teamName"));
    assertEquals(120, (int) results.json("$.rows[0].adjustedSeconds"));
    assertFalse(results.body().contains("teamId"));
    expectOk(put("/admin/publication", admin, "{\"published\":false}"));
    assertEquals(403, get("/results", member.token()).status());
  }

  @Test
  void csvExportIsRankedAndFormulaSafe() {
    TestTeam formula = team("=cmd", GroupCode.A);
    finish(formula, 60);
    Response csv = expectOk(get("/admin/export.csv", admin));
    String[] lines = csv.body().split("\r\n");
    assertEquals(
      "rank,team,group,clues,recoveryUsed,accusationAttempts,penaltyMinutes,penaltyEntries,finishedAt,adjustedSeconds",
      lines[0]
    );
    assertTrue(lines[1].startsWith("1,\"'=cmd\",A,"), lines[1]);
    assertTrue(lines[1].endsWith(",60"), lines[1]);
    assertEquals(403, get("/admin/export.csv", formula.token()).status());
  }

  /** Records a correct accusation and the team's finish {@code seconds} after the final started. */
  private void finish(TestTeam team, long seconds) {
    UUID accusation = UUID.randomUUID();
    Timestamp at = Timestamp.from(FINAL_STARTED.plusSeconds(seconds));
    db.update(
      """
      INSERT INTO accusation(id, team_id, suspect_id, decrypted_message, evidence, correct, received_at,
                             idempotency_key, request_hash)
      VALUES (?, ?, ?, 'message', 'evidence', TRUE, ?, ?, 'hash')""",
      accusation,
      team.id(),
      suspectId(CORRECT_SUSPECT),
      at,
      UUID.randomUUID()
    );
    db.update("INSERT INTO team_final(team_id, finished_at, finishing_accusation_id) VALUES (?, ?, ?)", team.id(), at, accusation);
    clues(team, 4);
  }

  private void penalty(TestTeam team, int minutes, boolean voided) {
    db.update(
      """
      INSERT INTO penalty(team_id, room_id, kind, minutes, created_by, created_at, voided_by, voided_at, void_reason)
      VALUES (?, ?, 'PROHIBITED_TOOL', ?, ?, now(), ?, ?, ?)""",
      team.id(),
      rooms.get(RoomCode.AI),
      minutes,
      adminId,
      voided ? adminId : null,
      voided ? Timestamp.from(Instant.now()) : null,
      voided ? "mistake" : null
    );
  }

  private void clues(TestTeam team, int count) {
    for (RoomCode code : List.of(RoomCode.values()).subList(0, count)) clue(team, code);
  }

  private void clue(TestTeam team, RoomCode code) {
    db.update(
      "INSERT INTO collected_clue(team_id, room_id, source, collected_at) VALUES (?, ?, 'RECOVERY', now())",
      team.id(),
      rooms.get(code)
    );
  }
}
