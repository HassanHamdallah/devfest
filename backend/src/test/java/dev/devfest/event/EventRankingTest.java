package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The 100-point ranking: total DESC, then final score DESC, then the earlier final finish; ties share a rank;
 * unqualified teams rank by their room scores; disqualified teams stay outside; scores reach teams only once published.
 */
class EventRankingTest extends EventIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-11-08T10:00:00Z");

  @BeforeEach
  void prepare() {
    loadContent();
  }

  @Test
  void roomScoresAddUpToTheTotalAndTotalDecidesFirst() {
    TestTeam high = team("High", GroupCode.A);
    TestTeam low = team("Low", GroupCode.B);
    TestTeam mid = team("Mid", GroupCode.C);
    manual(high, "14.5");
    manual(low, "3");
    manual(mid, "9.25");
    assertEquals(List.of("High", "Mid", "Low"), names());
    assertEquals(List.of(1, 2, 3), ranks());
    assertEquals(new BigDecimal("14.50"), total("High"));
    Response board = expectOk(get("/admin/board", admin));
    List<Number> roomScores = board.json("$.ranking[0].scores.rooms[*].score");
    assertEquals(List.of(0.0, 0.0, 0.0, 14.5), roomScores.stream().map(Number::doubleValue).toList());
    assertEquals("MANUAL", board.json("$.ranking[0].scores.rooms[3].source"));
  }

  @Test
  void equalTotalsAreSeparatedByTheFinalScore() {
    TestTeam withFinal = team("WithFinal", GroupCode.A);
    TestTeam roomsOnly = team("RoomsOnly", GroupCode.B);
    // 15 points from the final's four decrypted files against 15 points from the Hardware room.
    manual(roomsOnly, "15");
    cluesSolved(withFinal, 4);
    assertEquals(new BigDecimal("15.00"), total("WithFinal"));
    assertEquals(new BigDecimal("15.00"), total("RoomsOnly"));
    assertEquals(List.of("WithFinal", "RoomsOnly"), names());
    assertEquals(List.of(1, 2), ranks());
  }

  @Test
  void equalTotalAndFinalScoreAreSeparatedByTheEarlierFinish() {
    TestTeam late = team("Late", GroupCode.A);
    TestTeam early = team("Early", GroupCode.B);
    cluesSolved(late, 4);
    cluesSolved(early, 4);
    submitted(late, T0.plusSeconds(90), 600, false);
    submitted(early, T0.plusSeconds(30), 600, false);
    assertEquals(total("Late"), total("Early"));
    assertEquals(List.of("Early", "Late"), names());
    assertEquals(List.of(1, 2), ranks());
  }

  @Test
  void teamsEqualOnEverythingShareARankAndTheNextOneSkips() {
    TestTeam a = team("A", GroupCode.A);
    TestTeam b = team("B", GroupCode.B);
    TestTeam c = team("C", GroupCode.C);
    TestTeam d = team("D", GroupCode.D);
    manual(a, "10");
    manual(b, "10");
    manual(c, "5");
    manual(d, "0");
    assertEquals(List.of("A", "B", "C", "D"), names());
    assertEquals(List.of(1, 1, 3, 4), ranks());
  }

  @Test
  void teamsThatDidNotQualifyRankByTheirRoomScoresAndDisqualifiedTeamsStayOutside() {
    TestTeam qualified = team("Qualified", GroupCode.A);
    TestTeam unqualified = team("Unqualified", GroupCode.B);
    TestTeam cheater = team("Cheater", GroupCode.C);
    for (RoomCode code : RoomCode.values()) db.update("INSERT INTO collected_clue(team_id, room_id, source, collected_at) VALUES (?, ?, 'MANUAL', now())", qualified.id(), rooms.get(code));
    manual(qualified, "4");
    manual(unqualified, "12");
    manual(cheater, "15");
    expectOk(post("/admin/teams/" + cheater.id() + "/disqualify", admin, "{\"reason\":\"Broke the rules of the event\"}"));
    setRound(5);
    openFinal();
    Response board = expectOk(get("/admin/board", admin));
    assertEquals(List.of("Unqualified", "Qualified", "Cheater"), board.json("$.ranking[*].scores.teamName"));
    List<Integer> ranks = board.json("$.ranking[*].rank");
    assertEquals(Integer.valueOf(1), ranks.get(0));
    assertEquals(Integer.valueOf(2), ranks.get(1));
    assertNull(ranks.get(2), "a disqualified team has no rank");
    assertEquals("NOT_QUALIFIED", board.json("$.ranking[0].scores.finalScore.status"));
    assertEquals(new BigDecimal("0.00"), finalTotal("Unqualified"));
  }

  @Test
  void deductionsReduceTheTotalAndChangeTheOrder() {
    TestTeam a = team("A", GroupCode.A);
    TestTeam b = team("B", GroupCode.B);
    manual(a, "15");
    manual(b, "14");
    assertEquals(List.of("A", "B"), names());
    setRound(4);
    expectOk(post("/rooms/" + rooms.get(RoomCode.HARDWARE) + "/hints", admin, "{\"teamId\":" + a.id() + "}"));
    assertEquals(new BigDecimal("13.00"), total("A"));
    assertEquals(List.of("B", "A"), names());
    expectOk(post("/rooms/" + rooms.get(RoomCode.HARDWARE) + "/cheating", admin, "{\"teamId\":%d,\"reason\":\"Used a phone\",\"confirmed\":true}".formatted(b.id())));
    assertEquals(new BigDecimal("6.50"), total("B"));
    assertEquals(List.of("A", "B"), names());
  }

  @Test
  void scoresReachTeamsOnlyOnceTheAdminPublishes() {
    TestTeam a = team("A", GroupCode.A);
    TestTeam b = team("B", GroupCode.B);
    manual(a, "11");
    manual(b, "7.5");
    expectError(get("/results", a.token()), 403, "Results are not published yet.");
    assertEquals(403, get("/results", managers.get(RoomCode.AI)).status(), "managers never see the ranking");
    assertEquals(false, expectOk(get("/results", admin)).json("$.published"));
    expectOk(put("/admin/publication", admin, "{\"published\":true}"));
    Response published = expectOk(get("/results", a.token()));
    assertEquals(List.of("A", "B"), published.json("$.rows[*].teamName"));
    assertEquals(List.of(1, 2), published.json("$.rows[*].rank"));
    assertEquals(List.of(11.0, 7.5), ((List<?>) published.json("$.rows[*].total")).stream().map(n -> ((Number) n).doubleValue()).toList());
    assertEquals(4, (int) published.json("$.rows[0].roomScores.length()"));
    assertFalse(published.body().contains("hints") || published.body().contains("cheating") || published.body().contains("deduction"));
    expectOk(put("/admin/publication", admin, "{\"published\":false}"));
    expectError(get("/results", b.token()), 403, "Results are not published yet.");
  }

  @Test
  void theSpreadsheetFollowsTheRankingAndNeutralizesFormulas() {
    TestTeam a = team("=SUM(1+1)", GroupCode.A);
    team("Plain", GroupCode.B);
    manual(a, "8");
    Response csv = expectOk(get("/admin/export.csv", admin));
    String[] lines = csv.body().split("\r\n");
    assertEquals(
      "rank,team,group,status,aiRoom,problemSolvingRoom,linuxRoom,hardwareRoom,hintsUsed,cheatingRooms,roomsTotal,cluesSolved,cluePoints,suspect,time,finalScore,total,finishedAt",
      lines[0]
    );
    assertEquals(3, lines.length);
    assertTrue(lines[1].startsWith("1,"), lines[1]);
    assertFalse(lines[1].contains(",=SUM"), "formula-safe cell: " + lines[1]);
    assertTrue(lines[1].contains(",8.00,"), lines[1]);
    assertTrue(lines[2].startsWith("2,\"Plain\",B,LOCKED,"), lines[2]);
  }

  // ---- helpers ----------------------------------------------------------------------------------

  /** The Hardware room has no automatic score: the manager types it. */
  private void manual(TestTeam team, String score) {
    expectOk(put("/rooms/" + rooms.get(RoomCode.HARDWARE) + "/manual-scores/" + team.id(), managers.get(RoomCode.HARDWARE), "{\"score\":" + score + "}"));
  }

  /** {@code count} decrypted evidence files, recorded directly (the passphrases themselves are tested with the final). */
  private void cluesSolved(TestTeam team, int count) {
    for (int clue = 1; clue <= count; clue++) {
      db.update(
        "INSERT INTO final_clue_attempt(id, team_id, clue, value, correct, received_at, idempotency_key, request_hash) VALUES (?, ?, ?, ?, TRUE, ?, ?, 'x')",
        UUID.randomUUID(),
        team.id(),
        clue,
        PASSPHRASES.get(clue - 1),
        java.sql.Timestamp.from(T0),
        UUID.randomUUID()
      );
    }
  }

  private void submitted(TestTeam team, Instant at, int activeSeconds, boolean correct) {
    db.update(
      """
      INSERT INTO team_final(team_id, unlocked_at, case_started_at, suspect_id, suspect_correct, submitted_at, active_seconds, finalised_at, finalised_cause)
      VALUES (?, ?, ?, 'S05', ?, ?, ?, ?, 'SUBMITTED')""",
      team.id(),
      java.sql.Timestamp.from(T0),
      java.sql.Timestamp.from(T0),
      correct,
      java.sql.Timestamp.from(at),
      activeSeconds,
      java.sql.Timestamp.from(at)
    );
  }

  private List<String> names() {
    return expectOk(get("/admin/board", admin)).json("$.ranking[*].scores.teamName");
  }

  private List<Integer> ranks() {
    return expectOk(get("/admin/board", admin)).json("$.ranking[*].rank");
  }

  private BigDecimal total(String teamName) {
    List<Number> found = expectOk(get("/admin/scores", admin)).json("$[?(@.teamName == '" + teamName + "')].total");
    return new BigDecimal(found.get(0).toString()).setScale(2);
  }

  private BigDecimal finalTotal(String teamName) {
    List<Number> found = expectOk(get("/admin/scores", admin)).json("$[?(@.teamName == '" + teamName + "')].finalScore.total");
    return new BigDecimal(found.get(0).toString()).setScale(2);
  }
}
