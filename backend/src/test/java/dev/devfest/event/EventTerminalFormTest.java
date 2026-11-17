package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The team form of a LINUX_TERMINAL room (rules §18.7). Charlie (group C) is in the Linux room in round 1, Alpha
 * (group A) in the AI room, Bravo (group B) in the Linux room in round 2.
 */
class EventTerminalFormTest extends EventIntegrationTest {

  private static final String CLUE = CLUE_KEYS.get(RoomCode.LINUX);
  private static final String REASON = "\"reason\":\"Form test\"";
  private static final String DIFFERENT_REQUEST = "This Idempotency-Key was already used for a different request.";
  /** The only properties a team's form response may have: no outcome, score or key can hide among them. */
  private static final List<String> FORM_PROPERTIES = List.of(
    "roomId", "status", "path", "username", "fileSize", "pattern", "finalClue", "savedAt", "submittedAt"
  );

  private TestTeam alpha;
  private TestTeam charlie;
  private long linux;

  @BeforeEach
  void prepare() {
    loadContent();
    alpha = team("Alpha", GroupCode.A);
    charlie = team("Charlie", GroupCode.C);
    linux = rooms.get(RoomCode.LINUX);
    expectOk(put("/admin/rooms/" + linux + "/activity", admin, "{\"activity\":\"LINUX_TERMINAL\"}"));
  }

  private void startLinuxRound() {
    setRound(1);
    openRoom(RoomCode.LINUX);
  }

  // ---- saving ---------------------------------------------------------------------------------------

  @Test
  void aTeamSavesBlankAndFilledValuesRepeatedlyAndTheServerKeepsThem() {
    startLinuxRound();
    Response empty = expectOk(view(charlie));
    assertEquals("EMPTY", empty.json("$.status"));
    assertEquals("", empty.json("$.path"));

    Response blank = expectOk(save(charlie, UUID.randomUUID(), form("", "", "", "", "")));
    assertEquals("SAVED", blank.json("$.status"));

    long before = revision();
    Response saved = expectOk(save(charlie, UUID.randomUUID(), form("/srv/rami/uploads", "tech9", "1337c", "*.bak", "")));
    assertEquals("SAVED", saved.json("$.status"));
    assertEquals("tech9", saved.json("$.username"));
    assertNotNull(saved.json("$.savedAt"));
    assertNull(saved.<Object>json("$.submittedAt"));
    expectOk(save(charlie, UUID.randomUUID(), form("/srv/rami/uploads", "tech9", "1337c", "*.bak", "WEST TOWER")));
    assertEquals(before, revision(), "Save never bumps the event revision");

    // A refresh (a new GET) and a terminal restart read the same state from the server.
    Response again = expectOk(view(charlie));
    assertEquals("WEST TOWER", again.json("$.finalClue"));
    assertEquals("SAVED", again.json("$.status"));
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM room_submission", Integer.class), "saving is not submitting");
  }

  @Test
  void aRetryWithTheSameKeyIsAnsweredAgainAndAStaleRetryNeverOverwritesANewerSave() {
    startLinuxRound();
    UUID first = UUID.randomUUID();
    String firstBody = form("a", "", "", "", "");
    expectOk(save(charlie, first, firstBody));
    expectOk(save(charlie, UUID.randomUUID(), form("newer", "", "", "", "")));

    Response retry = expectOk(save(charlie, first, firstBody));
    assertEquals("newer", retry.json("$.path"), "the old key's retry is answered with the current state, not applied again");
    assertEquals(2, (int) db.queryForObject("SELECT count(*) FROM terminal_form_request", Integer.class));
    expectError(save(charlie, first, form("other", "", "", "", "")), 409, DIFFERENT_REQUEST);
    expectError(submit(charlie, first, firstBody), 409, DIFFERENT_REQUEST);
    assertEquals("newer", expectOk(view(charlie)).json("$.path"));
  }

  @Test
  void valuesAreLimitedToFiveHundredBytesAndMustBeText() {
    startLinuxRound();
    expectOk(save(charlie, UUID.randomUUID(), form("é".repeat(250), "", "", "", "")));
    expectError(save(charlie, UUID.randomUUID(), form("é".repeat(250) + "x", "", "", "", "")), 400, "PATH is too long (max 500 bytes).");
    expectError(save(charlie, UUID.randomUUID(), form("", "", "", "", "a\\u0000b")), 400, "RETRIEVED KEY must be text.");
    assertEquals(400, teamPost(charlie, formPath(linux) + "/save", UUID.randomUUID(), "{\"path\":\"x\"}").status());
  }

  // ---- refusals -------------------------------------------------------------------------------------

  @Test
  void nothingIsAcceptedOutsideTheTeamsRoundWhilePausedAfterTimeUpOrBeforeTheTimer() {
    expectNotFound(view(charlie), "round 0");
    assignRound(1);
    assertEquals("EMPTY", expectOk(view(charlie)).json("$.status"), "the form is visible while the round is assigned");
    expectError(save(charlie, UUID.randomUUID(), form("x", "", "", "", "")), 409, "The timer has not started yet.");
    startTimer();
    expectError(save(charlie, UUID.randomUUID(), form("x", "", "", "", "")), 409, "This room is closed.");
    openRoom(RoomCode.LINUX);

    expectOk(post("/admin/round/timer/pause", admin, null));
    expectError(save(charlie, UUID.randomUUID(), form("x", "", "", "", "")), 409, "The round is paused.");
    expectError(submit(charlie, UUID.randomUUID(), form("x", "", "", "", "")), 409, "The round is paused.");
    expectOk(post("/admin/round/timer/resume", admin, null));
    expectOk(save(charlie, UUID.randomUUID(), form("x", "", "", "", "")));

    expectOk(post("/admin/round/timer/end", admin, null));
    expectError(save(charlie, UUID.randomUUID(), form("late", "", "", "", "")), 409, "Time is up for this round.");
    assertEquals("SUBMITTED", expectOk(view(charlie)).json("$.status"), "a form with something saved is submitted at Time's up");
    assertEquals("x", expectOk(view(charlie)).json("$.path"));

    assignRound(2);
    expectNotFound(view(charlie), "a later round in another room");
    expectNotFound(save(charlie, UUID.randomUUID(), form("x", "", "", "", "")), "a later round in another room");
  }

  @Test
  void onlyTheAssignedTeamReachesTheFormAndEveryoneElseGetsA404OrAnAccessError() {
    startLinuxRound();
    expectNotFound(view(alpha), "another group");
    expectNotFound(save(alpha, UUID.randomUUID(), form("x", "", "", "", "")), "another group");
    expectNotFound(submit(alpha, UUID.randomUUID(), form("x", "", "", "", "")), "another group");
    // Not the activity room: the AI room has no form.
    assertEquals(404, get(formPath(rooms.get(RoomCode.AI)), charlie.token()).status());
    assertEquals(404, get(formPath(Long.MAX_VALUE - 1), charlie.token()).status());

    assertEquals(401, get(formPath(linux), null).status());
    expectError(get(formPath(linux), admin), 403, "A team account is required.");
    expectError(get(formPath(linux), managers.get(RoomCode.LINUX)), 403, "A team account is required.");
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM terminal_form", Integer.class));

    expectOk(post("/admin/teams/" + charlie.id() + "/disqualify", admin, "{" + REASON + "}"));
    expectError(save(charlie, UUID.randomUUID(), form("x", "", "", "", "")), 409, "Your team has been disqualified. Contact the organizer.");
    expectOk(post("/admin/teams/" + charlie.id() + "/reinstate", admin, "{" + REASON + "}"));

    expectOk(put("/admin/overrides/room-exception", admin, "{\"teamId\":%d,\"roomId\":%d,%s}".formatted(charlie.id(), rooms.get(RoomCode.AI), REASON)));
    expectNotFound(view(charlie), "moved out of the room by an exception");
    expectOk(put("/admin/overrides/room-exception", admin, "{\"teamId\":%d,\"roomId\":%d,%s}".formatted(alpha.id(), linux, REASON)));
    expectOk(save(alpha, UUID.randomUUID(), form("moved in", "", "", "", "")));
  }

  @Test
  void aRoomWithoutTheActivityHasNoFormAndNoActivityRoomTakesAPlainSubmission() {
    startLinuxRound();
    expectOk(put("/admin/rooms/" + linux + "/activity", admin, "{\"activity\":null}"));
    expectNotFound(view(charlie), "the activity was switched off");
    expectOk(submit(charlie, RoomCode.LINUX, UUID.randomUUID(), "output", CLUE));
    expectOk(put("/admin/rooms/" + linux + "/activity", admin, "{\"activity\":\"LINUX_TERMINAL\"}"));
    expectError(submit(charlie, RoomCode.LINUX, UUID.randomUUID(), "output", CLUE), 409, "This room records its result from its activity; there is no answer form.");
  }

  // ---- the final submit -----------------------------------------------------------------------------

  @Test
  void aCorrectFinalSubmitRecordsOneSubmissionCollectsTheClueAndLocksTheForm() {
    startLinuxRound();
    expectOk(save(charlie, UUID.randomUUID(), form("/srv", "tech9", "1c", "*.x", "")));
    long before = revision();
    UUID key = UUID.randomUUID();
    String body = form("/srv/rami/uploads", "tech9", "1337c", "*.bak", " " + CLUE.toLowerCase() + " ");
    Response submitted = expectOk(submit(charlie, key, body));
    assertEquals("SUBMITTED", submitted.json("$.status"));
    assertEquals("*.bak", submitted.json("$.pattern"), "the submit carries the screen's current values");
    assertNotNull(submitted.json("$.submittedAt"));
    assertTrue(revision() > before, "a submission bumps the revision");

    Map<String, Object> row = db.queryForMap("SELECT technical_output, clue_answer, clue_correct, round FROM room_submission");
    assertEquals(
      "Linux terminal form (submitted by the team)\nPATH: /srv/rami/uploads\nUSERNAME: tech9\nFILE_SIZE: 1337c\nFILE_NAME: *.bak\nRETRIEVED KEY:  " + CLUE.toLowerCase() + " ",
      row.get("technical_output")
    );
    assertEquals(true, row.get("clue_correct"));
    assertEquals(1, (int) row.get("round"));
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM collected_clue WHERE team_id = ? AND source = 'ROOM'", Integer.class, charlie.id()));
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM terminal_form WHERE submission_id IS NOT NULL AND cause = 'SUBMIT'", Integer.class));

    // Locked: a retry of the same request is answered again, anything new is refused.
    assertEquals("SUBMITTED", expectOk(submit(charlie, key, body)).json("$.status"));
    expectError(submit(charlie, UUID.randomUUID(), body), 409, "Your team has already submitted its final answer.");
    expectError(save(charlie, UUID.randomUUID(), body), 409, "Your team has already submitted its final answer.");
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM room_submission", Integer.class));

    // The room counts as finished for the team at once, and its clue shows in the evidence.
    Response board = expectOk(get("/team/board", charlie.token()));
    assertEquals(List.of(true), board.json("$.evidence[?(@.roomCode == 'LINUX')].finished"));
    assertEquals(List.of(CLUE), board.json("$.evidence[?(@.roomCode == 'LINUX')].clueValue"));
    assertEquals(List.of(true), board.json("$.evidence[?(@.roomCode == 'LINUX')].resultRecorded"));
    // The manager sees the submission to judge as for any room.
    Response managerBoard = expectOk(get("/rooms/" + linux + "/board", managers.get(RoomCode.LINUX)));
    assertTrue(managerBoard.body().contains("Linux terminal form (submitted by the team)"));
  }

  @Test
  void aBlankOrWrongClueIsRecordedButCollectsNoClue() {
    startLinuxRound();
    expectOk(submit(charlie, UUID.randomUUID(), form("/srv", "tech9", "1c", "*.x", "")));
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM collected_clue", Integer.class));
    assertEquals(false, db.queryForObject("SELECT clue_correct FROM room_submission", Boolean.class));
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM room_submission", Integer.class));
    assertEquals(List.of(true), expectOk(get("/team/board", charlie.token())).json("$.evidence[?(@.roomCode == 'LINUX')].finished"));

    TestTeam bravo = team("Bravo", GroupCode.B);
    setRound(2);
    expectOk(submit(bravo, UUID.randomUUID(), form("", "", "", "", "NOT THE CLUE")));
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM collected_clue WHERE team_id = ?", Integer.class, bravo.id()));
  }

  @Test
  void aNewClueKeyRechecksAFormAnswerLikeAnyOtherSubmission() {
    startLinuxRound();
    expectOk(submit(charlie, UUID.randomUUID(), form("", "", "", "", "NEW KEY")));
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM collected_clue", Integer.class));
    expectOk(setClueKey(RoomCode.LINUX, "NEW KEY"));
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM collected_clue WHERE team_id = ?", Integer.class, charlie.id()));
  }

  // ---- the round's end ------------------------------------------------------------------------------

  @Test
  void timeUpSubmitsEverySavedFormOnceAndLeavesTheRestAlone() {
    TestTeam saved = team("Saved", GroupCode.C);
    TestTeam blank = team("Blank", GroupCode.C);
    team("None", GroupCode.C);
    TestTeam disqualified = team("Out", GroupCode.C);
    TestTeam submitted = team("Done", GroupCode.C);
    team("Elsewhere", GroupCode.D);
    startLinuxRound();
    expectOk(save(saved, UUID.randomUUID(), form("/p", "u", "1", "n", CLUE)));
    expectOk(save(blank, UUID.randomUUID(), form("", " ", "", "", "")));
    expectOk(save(disqualified, UUID.randomUUID(), form("x", "", "", "", CLUE)));
    expectOk(post("/admin/teams/" + disqualified.id() + "/disqualify", admin, "{" + REASON + "}"));
    expectOk(submit(submitted, UUID.randomUUID(), form("done", "", "", "", "wrong")));
    long before = revision();

    expectOk(post("/admin/round/timer/end", admin, null));
    assertTrue(revision() > before);
    assertEquals(
      List.of("Done", "Saved"),
      db.queryForList("SELECT t.name FROM room_submission s JOIN event_team t ON t.id = s.team_id ORDER BY t.name", String.class)
    );
    assertEquals(List.of("SUBMIT", "TIME_UP"), db.queryForList("SELECT cause FROM terminal_form WHERE submission_id IS NOT NULL ORDER BY cause", String.class));
    String output = db.queryForObject("SELECT technical_output FROM room_submission s JOIN event_team t ON t.id = s.team_id WHERE t.name = 'Saved'", String.class);
    assertTrue(output.startsWith("Linux terminal form (saved by the team, submitted at Time's up)\nPATH: /p\n"), output);
    assertEquals(List.of("Saved"), db.queryForList("SELECT t.name FROM collected_clue c JOIN event_team t ON t.id = c.team_id", String.class));
    assertEquals("SAVED", expectOk(get(formPath(linux), blank.token())).json("$.status"), "a form with only blanks is not submitted");
    assertEquals("SAVED", expectOk(get(formPath(linux), disqualified.token())).json("$.status"));

    // Ending again, and moving on, change nothing.
    expectOk(post("/admin/round/timer/end", admin, null));
    long afterEnd = revision();
    assignRound(2);
    assertEquals(2, (int) db.queryForObject("SELECT count(*) FROM room_submission", Integer.class));
    assertEquals(afterEnd + 1, revision(), "only the round change bumped it");
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM room_submission s JOIN event_team t ON t.id = s.team_id WHERE t.name IN ('None', 'Elsewhere')", Integer.class));
  }

  @Test
  void movingToTheNextRoundSubmitsTheFormsOfTheRoundThatEnds() {
    startLinuxRound();
    expectOk(save(charlie, UUID.randomUUID(), form("/p", "", "", "", CLUE)));
    assignRound(2);
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM room_submission WHERE round = 1", Integer.class));
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM collected_clue WHERE team_id = ?", Integer.class, charlie.id()));
    assignRound(3);
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM room_submission", Integer.class));
  }

  @Test
  void aFinalSubmitRacingTheRoundEndRecordsOneSubmissionAndOneClue() throws Exception {
    // One team per group; each is in the Linux room in a different round, so the race runs four times.
    List<TestTeam> racers = new ArrayList<>();
    for (GroupCode group : GroupCode.values()) racers.add(team("Racer " + group, group));
    racers.sort(java.util.Comparator.comparingInt(team -> roundFor(team.group(), RoomCode.LINUX)));
    for (TestTeam racer : racers) {
      int round = roundFor(racer.group(), RoomCode.LINUX);
      setRound(round);
      openRoom(RoomCode.LINUX);
      String body = form("/p", "u", "1", "n", CLUE);
      expectOk(save(racer, UUID.randomUUID(), body));

      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      Future<Integer> submitting = pool.submit(() -> {
        start.await();
        return submit(racer, UUID.randomUUID(), body).status();
      });
      Future<Integer> ending = pool.submit(() -> {
        start.await();
        return post("/admin/round/timer/end", admin, null).status();
      });
      start.countDown();
      int submitStatus = submitting.get(20, TimeUnit.SECONDS);
      int endStatus = ending.get(20, TimeUnit.SECONDS);
      pool.shutdown();

      assertEquals(200, endStatus, "round " + round);
      assertTrue(submitStatus == 200 || submitStatus == 409, "the team's submit either won or found Time's up: " + submitStatus);
      assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM room_submission WHERE team_id = ?", Integer.class, racer.id()), "round " + round);
      assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM collected_clue WHERE team_id = ?", Integer.class, racer.id()), "round " + round);
      assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM terminal_form WHERE team_id = ? AND submission_id IS NOT NULL", Integer.class, racer.id()), "round " + round);
    }
  }

  @Test
  void theSameFinalSubmitSentTwiceAtOnceIsOneSubmission() throws Exception {
    startLinuxRound();
    UUID key = UUID.randomUUID();
    String body = form("/p", "u", "1", "n", CLUE);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Callable<Response> send = () -> {
      start.await();
      return submit(charlie, key, body);
    };
    Future<Response> a = pool.submit(send);
    Future<Response> b = pool.submit(send);
    start.countDown();
    assertEquals(200, a.get(20, TimeUnit.SECONDS).status());
    assertEquals(200, b.get(20, TimeUnit.SECONDS).status());
    pool.shutdown();
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM room_submission", Integer.class));
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM collected_clue", Integer.class));
  }

  // ---- what the team never sees ---------------------------------------------------------------------

  @Test
  void aTeamNeverSeesAnOutcomeAScoreOrAKeyInAnyStateOfItsForm() {
    startLinuxRound();
    List<String> bodies = new ArrayList<>();
    bodies.add(expectOk(view(charlie)).body());
    bodies.add(expectOk(save(charlie, UUID.randomUUID(), form("/srv", "tech9", "1c", "*.x", "guess"))).body());
    bodies.add(expectOk(view(charlie)).body());
    for (String body : bodies) {
      assertEquals(FORM_PROPERTIES.stream().sorted().toList(), propertiesOf(body).stream().sorted().toList(), body);
    }
    bodies.add(expectOk(submit(charlie, UUID.randomUUID(), form("/srv", "tech9", "1c", "*.x", "guess"))).body());
    bodies.add(expectOk(view(charlie)).body());
    bodies.add(expectOk(get("/team/board", charlie.token())).body());
    bodies.add(expectOk(get("/team/submissions", charlie.token())).body());
    bodies.add(expectOk(get("/home", charlie.token())).body());
    for (String body : bodies) {
      for (String forbidden : List.of(CLUE, "\"clueCorrect\":true", "\"clueCorrect\":false", "\"correct\"", "score", "points", "passScore", "effectiveCorrect")) {
        assertFalse(body.contains(forbidden), forbidden + " must not reach the team: " + body);
      }
    }
    Response board = expectOk(get("/team/board", charlie.token()));
    assertEquals(java.util.Collections.singletonList(null), board.json("$.rooms[?(@.code == 'LINUX')].lastReceipt"), "no receipt, so no correctness");
    assertEquals(List.of(), expectOk(get("/team/submissions", charlie.token())).json("$[*]"));
    assertEquals(List.of(), board.json("$.evidence[?(@.roomCode == 'LINUX' && @.technicalOutput != null)]"));
    assertEquals(List.of(), board.json("$.evidence[?(@.roomCode == 'LINUX' && @.clueAnswer != null)]"));
    assertEquals(List.of(), board.json("$.evidence[?(@.roomCode == 'LINUX' && @.clueValue != null)]"), "a wrong clue is not collected");
  }

  // ---- reset and snapshot ---------------------------------------------------------------------------

  @Test
  void resettingATeamAndTheEventDeleteTheFormsAfterASnapshotThatIncludesThem() {
    startLinuxRound();
    expectOk(save(charlie, UUID.randomUUID(), form("/srv", "", "", "", "")));
    expectOk(submit(charlie, UUID.randomUUID(), form("/srv", "", "", "", CLUE)));

    Response reset = expectOk(post("/admin/reset/team/" + charlie.id(), admin, "{" + REASON + "}"));
    long snapshot = ((Number) reset.json("$.snapshotId")).longValue();
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM terminal_form", Integer.class));
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM terminal_form_request", Integer.class));
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM room_submission", Integer.class));
    Response download = call(org.springframework.http.HttpMethod.GET, "/admin/snapshots/" + snapshot + "/download", admin, null, null);
    assertTrue(download.body().contains("terminal_form"), "the snapshot holds the team's form");
    assertTrue(download.body().contains("terminal_form_request"));

    // An organizer reset signs the team out; it signs in again and starts over: saving works again.
    String email = db.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, charlie.accountId());
    charlie = new TestTeam(charlie.id(), charlie.name(), charlie.group(), charlie.accountId(), login(email));
    assertEquals("EMPTY", expectOk(view(charlie)).json("$.status"));
    expectOk(save(charlie, UUID.randomUUID(), form("again", "", "", "", "")));
    String body = "{\"confirmText\":\"RESET\",\"password\":\"%s\",%s}".formatted(PASSWORD, REASON);
    Response event = expectOk(post("/admin/reset/event", admin, body));
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM terminal_form", Integer.class));
    Response eventDownload = call(org.springframework.http.HttpMethod.GET, "/admin/snapshots/" + ((Number) event.json("$.snapshotId")).longValue() + "/download", admin, null, null);
    assertTrue(eventDownload.body().contains("\"again\""), "the event snapshot holds the saved form");
  }

  // ---- helpers ------------------------------------------------------------------------------------

  private static String formPath(long roomId) {
    return "/team/rooms/" + roomId + "/terminal-form";
  }

  private Response view(TestTeam team) {
    return get(formPath(linux), team.token());
  }

  private Response save(TestTeam team, UUID key, String body) {
    return teamPost(team, formPath(linux) + "/save", key, body);
  }

  private Response submit(TestTeam team, UUID key, String body) {
    return teamPost(team, formPath(linux) + "/submit", key, body);
  }

  private static String form(String path, String username, String fileSize, String pattern, String clue) {
    return "{\"path\":%s,\"username\":%s,\"fileSize\":%s,\"pattern\":%s,\"finalClue\":%s}".formatted(
      quote(path), quote(username), quote(fileSize), quote(pattern), quote(clue)
    ).replace("\\\\u0000", "\\u0000");
  }

  private static void expectNotFound(Response response, String situation) {
    assertEquals(404, response.status(), situation + ": " + response.body());
    assertEquals("Form not found.", response.detail(), situation);
  }

  @SuppressWarnings("unchecked")
  private static List<String> propertiesOf(String json) {
    return List.copyOf(((Map<String, Object>) com.jayway.jsonpath.JsonPath.read(json, "$")).keySet());
  }
}
