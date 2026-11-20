package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * D1–D7: a team looks back at a room (its answer and, when collected, the official clue) only once the room is
 * finished for it; clue keys of uncollected rooms and other teams' data never reach it. Alpha (group A) visits AI
 * in round 1; Bravo (group B) visits Problem solving in round 1.
 */
class EventEvidenceTest extends EventIntegrationTest {

  private static final String OUTPUT = "Validation accuracy 0.94 after tuning";
  private static final String AI_KEY = CLUE_KEYS.get(RoomCode.AI);

  @MockitoSpyBean
  private JdbcTemplate spiedJdbc;

  private TestTeam alpha;
  private TestTeam bravo;

  @BeforeEach
  void prepare() {
    loadContent();
    alpha = team("Alpha", GroupCode.A);
    bravo = team("Bravo", GroupCode.B);
  }

  @Test
  void whileTheTeamsRoundRunsTheRoomIsNotFinishedAndNoKeyIsSent() {
    setRound(1);
    openRoom(RoomCode.AI);
    expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), OUTPUT, "  white   van "));

    Response board = expectOk(get("/team/board", alpha.token()));
    assertEquals(false, evidence(board, RoomCode.AI, "finished"));
    assertEquals(true, evidence(board, RoomCode.AI, "clueCollected"));
    assertEquals(null, evidence(board, RoomCode.AI, "clueValue"));
    assertEquals(null, evidence(board, RoomCode.AI, "technicalOutput"));
    assertFalse(board.body().contains(AI_KEY), board.body());
    assertFalse(board.body().contains(OUTPUT), "evidence waits until the room is finished");
    assertEquals(4, (int) board.json("$.evidence.length()"));
  }

  @Test
  void afterTimeUpAndInLaterRoundsTheTeamSeesItsAnswerAndTheOfficialKey() {
    setRound(1);
    openRoom(RoomCode.AI);
    expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), OUTPUT, "  white   van "));
    expectOk(post("/admin/round/timer/end", admin, null));

    Response board = expectOk(get("/team/board", alpha.token()));
    assertEquals(true, evidence(board, RoomCode.AI, "finished"));
    assertEquals(OUTPUT, evidence(board, RoomCode.AI, "technicalOutput"));
    assertEquals("  white   van ", evidence(board, RoomCode.AI, "clueAnswer"));
    assertEquals(true, evidence(board, RoomCode.AI, "clueCorrect"));
    assertEquals(true, evidence(board, RoomCode.AI, "clueCollected"));
    assertEquals(AI_KEY, evidence(board, RoomCode.AI, "clueValue"));
    assertEquals(false, evidence(board, RoomCode.PROBLEM_SOLVING, "finished"), "round 2 is still ahead");

    setRound(2);
    assertEquals(AI_KEY, evidence(expectOk(get("/team/board", alpha.token())), RoomCode.AI, "clueValue"));
  }

  @Test
  void anIncorrectAnswerInAFinishedRoomShowsNoKey() {
    setRound(1);
    openRoom(RoomCode.AI);
    expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), OUTPUT, "BLUE CAR"));
    setRound(2);

    Response board = expectOk(get("/team/board", alpha.token()));
    assertEquals(true, evidence(board, RoomCode.AI, "finished"));
    assertEquals("BLUE CAR", evidence(board, RoomCode.AI, "clueAnswer"));
    assertEquals(false, evidence(board, RoomCode.AI, "clueCorrect"));
    assertEquals(false, evidence(board, RoomCode.AI, "clueCollected"));
    assertEquals(null, evidence(board, RoomCode.AI, "clueValue"));
    assertFalse(board.body().contains(AI_KEY), board.body());
  }

  @Test
  void anAcceptedNearMissShowsTheOfficialKeyWhichFollowsKeyChanges() {
    setRound(1);
    openRoom(RoomCode.AI);
    String id = expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), OUTPUT, "WHITE VANN")).json("$.id");
    String path = "/rooms/" + rooms.get(RoomCode.AI) + "/submissions/" + id + "/clue-override";
    expectOk(put(path, managers.get(RoomCode.AI), "{\"override\":\"ACCEPT\",\"version\":1}"));
    expectOk(post("/admin/round/timer/end", admin, null));

    Response board = expectOk(get("/team/board", alpha.token()));
    assertEquals("WHITE VANN", evidence(board, RoomCode.AI, "clueAnswer"));
    assertEquals(true, evidence(board, RoomCode.AI, "clueCorrect"));
    assertEquals(AI_KEY, evidence(board, RoomCode.AI, "clueValue"), "the official key, not the team's text");

    expectOk(setClueKey(RoomCode.AI, "WHITE TRUCK"));
    Response changed = expectOk(get("/team/board", alpha.token()));
    assertEquals("WHITE TRUCK", evidence(changed, RoomCode.AI, "clueValue"), "the current key is shown");
    assertFalse(changed.body().contains("\"" + AI_KEY + "\""), changed.body());
  }

  @Test
  void aManualClueForANeverVisitedRoomIsShownOnceThatRoomIsFinished() {
    setRound(1);
    String grant = "{\"teamId\":%d,\"roomId\":%d,\"action\":\"GRANT\",\"alsoRemoveRecovery\":false,\"reason\":\"Lost sheet\"}";
    expectOk(post("/admin/overrides/clue", admin, grant.formatted(alpha.id(), rooms.get(RoomCode.LINUX))));
    String linuxKey = CLUE_KEYS.get(RoomCode.LINUX);

    Response roundOne = expectOk(get("/team/board", alpha.token()));
    assertEquals(true, evidence(roundOne, RoomCode.LINUX, "clueCollected"));
    assertEquals(null, evidence(roundOne, RoomCode.LINUX, "clueValue"), "Linux is round 3 for group A");
    assertFalse(roundOne.body().contains(linuxKey), roundOne.body());

    setRound(5);
    Response finished = expectOk(get("/team/board", alpha.token()));
    assertEquals(true, evidence(finished, RoomCode.LINUX, "finished"));
    assertEquals(null, evidence(finished, RoomCode.LINUX, "technicalOutput"), "never submitted there");
    assertEquals(null, evidence(finished, RoomCode.LINUX, "clueAnswer"));
    assertEquals(linuxKey, evidence(finished, RoomCode.LINUX, "clueValue"));
  }

  @Test
  void aSuccessfulRecoveryAddsThatRoomsKeyToTheFinalEvidence() {
    collectClues(alpha, RoomCode.AI, RoomCode.PROBLEM_SOLVING, RoomCode.LINUX);
    setRound(5);
    openFinal();
    String hardwareKey = CLUE_KEYS.get(RoomCode.HARDWARE);
    Response before = expectOk(get("/team/final", alpha.token()));
    assertEquals(CLUE_KEYS.get(RoomCode.LINUX), evidence(before, RoomCode.LINUX, "clueValue"));
    assertEquals(null, evidence(before, RoomCode.HARDWARE, "clueValue"));
    assertFalse(before.body().contains(hardwareKey), before.body());

    finishRecovery(alpha);
    Response after = expectOk(get("/team/final", alpha.token()));
    assertEquals(true, evidence(after, RoomCode.HARDWARE, "clueCollected"));
    assertEquals(hardwareKey, evidence(after, RoomCode.HARDWARE, "clueValue"));
    assertEquals(List.of(java.util.Map.of("roomName", "Hardware", "key", hardwareKey)), after.json("$.unlock.recoveredKeys"));
  }

  @Test
  void anotherTeamNeverSeesThisTeamsAnswersOrKeys() {
    setRound(1);
    openRoom(RoomCode.AI);
    openRoom(RoomCode.PROBLEM_SOLVING);
    expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), OUTPUT, AI_KEY));
    expectOk(submit(bravo, RoomCode.PROBLEM_SOLVING, UUID.randomUUID(), "Bravo output", "wrong"));
    setRound(5);
    openFinal();

    for (String path : List.of("/team/board", "/team/final")) {
      String body = expectOk(get(path, bravo.token())).body();
      for (String hidden : List.of(OUTPUT, AI_KEY, CLUE_KEYS.get(RoomCode.PROBLEM_SOLVING))) {
        assertFalse(body.contains(hidden), hidden + " reached Bravo on " + path + ": " + body);
      }
    }
    Response bravoBoard = expectOk(get("/team/board", bravo.token()));
    assertEquals("Bravo output", evidence(bravoBoard, RoomCode.PROBLEM_SOLVING, "technicalOutput"));
    assertEquals(null, evidence(bravoBoard, RoomCode.AI, "technicalOutput"));
  }

  @Test
  void theTeamBoardUsesFiveStatementsWhateverTheNumberOfTeams() {
    setRound(1);
    openRoom(RoomCode.AI);
    expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), OUTPUT, AI_KEY));
    List<String> few = boardStatements();

    for (TestTeam team : teams(4)) {
      if (team.group() == GroupCode.A) expectOk(submit(team, RoomCode.AI, UUID.randomUUID(), "o", "x"));
    }
    expectOk(post("/admin/round/timer/end", admin, null));
    List<String> many = boardStatements();

    assertEquals(5, few.size(), few.toString());
    assertEquals(few, many);
  }

  /**
   * The SQL texts of one GET /team/board without the request's session check (auth_session), with consecutive
   * repeats collapsed (JdbcTemplate overloads call each other on the spy).
   */
  private List<String> boardStatements() {
    expectOk(get("/team/board", alpha.token()));
    clearInvocations(spiedJdbc);
    expectOk(get("/team/board", alpha.token()));
    List<String> statements = new ArrayList<>();
    for (Invocation invocation : mockingDetails(spiedJdbc).getInvocations()) {
      Object[] args = invocation.getArguments();
      if (args.length == 0 || !(args[0] instanceof String sql) || sql.contains("auth_session")) continue;
      if (statements.isEmpty() || !statements.getLast().equals(sql)) statements.add(sql);
    }
    return statements;
  }

  private static Object evidence(Response response, RoomCode room, String field) {
    List<Object> values = response.json("$.evidence[?(@.roomCode == '" + room + "')]." + field);
    assertEquals(1, values.size(), room + " " + field + " in " + Arrays.toString(values.toArray()));
    return values.getFirst();
  }
}
