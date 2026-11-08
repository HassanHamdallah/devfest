package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.sentinel.SentinelFixture;
import dev.devfest.event.service.SentinelScoreService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The AI mission room (rules §19) through the real API. Alpha and Alfa (group A) are in the AI room in round 1;
 * Bravo (group B) is in the Problem solving room until round 4. Rounds last 5 minutes (well inside the access
 * token lifetime). The room runs the shipped docs/ai-room/sentinel-content.json (100 points); the pass score is 60
 * unless a test sets it.
 */
@Import(ManualClock.Config.class)
class EventAiRoomTest extends EventIntegrationTest {

  private static final String AI_KEY = CLUE_KEYS.get(RoomCode.AI);
  private static final String EVALUATED = "Your team's result for this room is already recorded.";
  private static final String SAVED = "This stage is already saved.";
  private static final String NO_ACTIVITY = "Activity not found.";
  private static final String FAILED_TEXT = "SENTINEL UNSTABLE. Some systems could not be restored. Your team did not reach the required score.";
  private static final String PASSED_TEXT = "SENTINEL RESTORED. All systems are back online. Recovered record:";
  private static final List<String> PERFECT = SentinelFixture.PERFECT;
  /** No member response may carry any of these keys, in any state. */
  private static final Set<String> FORBIDDEN_KEYS = Set.of(
    "points", "maxPoints", "pointsEarned", "award", "awards", "score", "scores", "total", "passScore", "passed",
    "correct", "correctOption", "answer", "expected", "role", "explanation", "derived", "maximum", "flaggedAll"
  );

  @Autowired
  private ManualClock clock;

  @Autowired
  private ObjectMapper json;

  @Autowired
  private SentinelScoreService scoreService;

  @MockitoSpyBean
  private JdbcTemplate spiedJdbc;

  private long ai;
  private TestTeam alpha;
  private TestTeam alfa;

  @BeforeEach
  void prepare() {
    clock.reset();
    loadContent();
    ai = rooms.get(RoomCode.AI);
    alpha = team("Alpha", GroupCode.A);
    alfa = team("Alfa", GroupCode.A);
    expectOk(put("/admin/settings", admin, "{\"roundMinutes\":5}"));
    expectOk(setActivity(ai, "\"AI_QUIZ\"", admin));
    expectOk(importMission(SentinelFixture.shipped()));
  }

  // ---- what teams see -----------------------------------------------------------------------------

  @Test
  void memberResponsesNeverContainPointsScoresOrAnswersInAnyState() {
    assignRound(1);
    openRoom(RoomCode.AI);
    Response waiting = expectOk(view(alpha));
    assertEquals("ASSIGNED", waiting.json("$.phase"));
    assertEquals(true, waiting.json("$.ready"));
    assertEquals(List.of(), waiting.json("$.missions"), "nothing of the mission is sent before the timer starts");
    assertTrue(((String) waiting.json("$.intro")).startsWith("SENTINEL OFFLINE."));
    assertNull(waiting.json("$.result"));
    assertNoSecrets(waiting.body());

    startTimer();
    Response playing = expectOk(view(alpha));
    assertEquals("RUNNING", playing.json("$.phase"));
    assertEquals(List.of("PREDICT", "SEE", "NOOR"), playing.json("$.missions[*].title"));
    assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), playing.json("$.missions[*].stages[*].number"));
    assertNoSecrets(playing.body());
    for (String table : List.of("flagged", "train", "validation", "reply", "chunks", "role")) {
      assertFalse(playing.body().contains("\"" + table + "\""), "the whole experiment table must not be sent: " + table);
    }

    Response run = expectOk(run(alpha, key(), 1, "iqr-3"));
    assertNoSecrets(run.body());
    assertEquals(List.of("g13", "g14"), run.json("$.result.flagged"));
    Response afterRun = expectOk(view(alpha));
    assertEquals(List.of("iqr-3"), afterRun.json("$.missions[0].stages[0].runs[*].config"));
    assertEquals(1, countOccurrences(afterRun.body(), "\"flagged\""), "one run, one result");
    assertNoSecrets(afterRun.body());

    Response saved = expectOk(submitStage(alpha, key(), 1, PERFECT.get(0)));
    assertEquals("Saved", saved.json("$.message"));
    assertNoSecrets(saved.body());
    Response afterSave = expectOk(view(alpha));
    assertEquals(true, afterSave.json("$.missions[0].stages[0].saved"));
    assertNull(afterSave.json("$.missions[0].stages[0].data"), "a saved stage shows only that it is saved");
    assertNull(afterSave.json("$.missions[0].stages[0].brief"));
    assertEquals(List.of(), afterSave.json("$.missions[0].stages[0].runs"));
    assertEquals(1, (int) afterSave.json("$.savedCount"));
    assertNoSecrets(afterSave.body());

    expectOk(post("/admin/round/timer/pause", admin, null));
    Response paused = expectOk(view(alpha));
    assertEquals("PAUSED", paused.json("$.phase"));
    assertEquals(3, (int) paused.json("$.missions.length()"), "paused: the screen stays, input is frozen");
    assertNoSecrets(paused.body());
    expectOk(post("/admin/round/timer/resume", admin, null));

    // Alfa saves all ten stages: evaluated at once and passed.
    saveStages(alfa, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    Response passed = expectOk(view(alfa));
    assertEquals(true, passed.json("$.result.clueUnlocked"));
    assertEquals(AI_KEY, passed.json("$.result.clue"));
    assertEquals(PASSED_TEXT, passed.json("$.result.message"));
    assertEquals(List.of(), passed.json("$.missions"));
    assertNoSecrets(passed.body());

    // Alpha ran out of time with 10 points: evaluated at the round's end, not passed.
    clock.advance(Duration.ofMinutes(5).plusSeconds(1));
    Response timeUp = expectOk(view(alpha));
    assertEquals("TIME_UP", timeUp.json("$.phase"));
    assertEquals(List.of(), timeUp.json("$.missions"));
    assertNull(timeUp.json("$.result"), "the result waits for the round's end");
    expectOk(post("/admin/round/timer/end", admin, null));
    Response failed = expectOk(view(alpha));
    assertEquals(false, failed.json("$.result.clueUnlocked"));
    assertNull(failed.json("$.result.clue"));
    assertEquals(FAILED_TEXT, failed.json("$.result.message"));
    assertFalse(failed.body().contains(AI_KEY), "no clue without a pass");
    assertFalse(((String) failed.json("$.result.message")).chars().anyMatch(Character::isDigit), "never a number");
    assertNoSecrets(failed.body());

    String board = expectOk(get("/team/board", alpha.token())).body();
    String receipts = expectOk(get("/team/submissions", alpha.token())).body();
    for (String body : List.of(board, receipts)) {
      for (String secret : List.of("pointsEarned", "passScore", "total", "10.00", "AI mission result", "stages 1/10")) {
        assertFalse(body.contains(secret), secret + " in " + body);
      }
    }
    assertEquals("[]", receipts, "the evaluation is not one of the team's receipts");
    String evaluation = db.queryForObject("SELECT submission_id::text FROM sentinel_evaluation WHERE team_id = ?", String.class, alpha.id());
    expectError(get("/team/submissions/" + evaluation, alpha.token()), 404, "Submission not found.");
    assertEquals(List.of(true), JsonPathList.read(board, "$.evidence[?(@.roomCode == 'AI')].finished"), "the room is finished at once");
    assertEquals(List.of(), JsonPathList.read(board, "$.evidence[?(@.roomCode == 'AI' && @.clueCorrect != null)]"));
  }

  // ---- experiments --------------------------------------------------------------------------------

  @Test
  void repeatsAreFreeAndTheStageBudgetsAreEnforcedPerTeam() {
    startRound1();
    Response first = expectOk(run(alpha, key(), 3, "knn-1"));
    assertEquals(100, intAt(first, "$.result.train"));
    assertEquals(71, intAt(first, "$.result.validation"));
    assertEquals(3, (int) first.json("$.runsLeft"));
    Response repeat = expectOk(run(alpha, key(), 3, "knn-1"));
    assertEquals(3, (int) repeat.json("$.runsLeft"), "a repeat costs nothing");
    assertEquals(intAt(first, "$.result.validation"), intAt(repeat, "$.result.validation"));
    assertEquals(2, (int) expectOk(run(alpha, key(), 3, "knn-5")).json("$.runsLeft"));
    assertEquals(1, (int) expectOk(run(alpha, key(), 3, "tree-2")).json("$.runsLeft"));
    assertEquals(0, (int) expectOk(run(alpha, key(), 3, "tree-5")).json("$.runsLeft"));
    expectError(run(alpha, key(), 3, "knn-15"), 409, "You have used all the experiments of this stage.");
    expectError(run(alpha, key(), 3, "tree-20"), 409, "You have used all the experiments of this stage.");
    assertEquals(0, (int) expectOk(run(alpha, key(), 3, "knn-5")).json("$.runsLeft"), "an old run is still free when the budget is spent");
    assertEquals(4, count("SELECT count(*) FROM sentinel_run WHERE team_id = " + alpha.id() + " AND stage = 3"));
    Response view = expectOk(view(alpha));
    assertEquals(List.of("knn-1", "knn-5", "tree-2", "tree-5"), view.json("$.missions[0].stages[2].runs[*].config"), "runs come back in the order they were run");
    assertEquals(0, (int) view.json("$.missions[0].stages[2].runsLeft"));
    assertEquals(89, intAt(view, "$.missions[0].stages[2].runs[3].result.validation"));

    for (String config : List.of("iqr-1.5", "iqr-3", "z-2", "z-3")) expectOk(run(alpha, key(), 1, config));
    assertNull(expectOk(run(alpha, key(), 1, "iqr-3")).json("$.runsLeft"), "Outlier Hunt has no limit");
    assertEquals(List.of(), expectOk(run(alpha, key(), 1, "z-3")).json("$.result.flagged"));

    for (String config : List.of("small-1", "small-3", "large-1")) expectOk(run(alpha, key(), 10, config));
    expectError(run(alpha, key(), 10, "large-3"), 409, "You have used all the experiments of this stage.");
    Response rag = expectOk(run(alpha, key(), 10, "large-1"));
    assertEquals(1, (int) rag.json("$.result.chunks.length()"));
    assertTrue(((String) rag.json("$.result.reply")).contains("Library"));

    assertEquals(3, (int) expectOk(run(alfa, key(), 3, "knn-1")).json("$.runsLeft"), "budgets are per team");
    expectError(run(alpha, key(), 2, "anything"), 400, "This stage has no experiments.");
    expectError(run(alpha, key(), 3, "nope"), 400, "Choose one of the stage's configurations.");
    expectError(run(alpha, key(), 11, "knn-1"), 404, "Stage not found.");
    expectError(run(alpha, key(), 0, "knn-1"), 404, "Stage not found.");
    assertEquals(400, teamPost(alpha, "/team/rooms/" + ai + "/activity/stages/3/runs", key(), "{}").status());
    assertEquals(400, teamPost(alpha, "/team/rooms/" + ai + "/activity/stages/3/runs", key(), "{\"config\":\"knn-1\",\"extra\":1}").status());

    expectOk(submitStage(alpha, key(), 3, PERFECT.get(2)));
    expectError(run(alpha, key(), 3, "knn-1"), 409, SAVED);
  }

  @Test
  void aRetryOfARunReplaysAndAnotherBodyWithTheSameKeyIsRefused() {
    startRound1();
    UUID key = key();
    Response first = expectOk(run(alpha, key, 3, "knn-5"));
    assertEquals(first.body(), expectOk(run(alpha, key, 3, "knn-5")).body());
    expectError(run(alpha, key, 3, "tree-5"), 409, "This Idempotency-Key was already used for a different request.");
    assertEquals(1, count("SELECT count(*) FROM sentinel_run WHERE team_id = " + alpha.id()));
    assertEquals(400, call(org.springframework.http.HttpMethod.POST, "/team/rooms/" + ai + "/activity/stages/3/runs", alpha.token(), "{\"config\":\"knn-1\"}", null).status(), "the Idempotency-Key header is required");
  }

  // ---- stage submissions --------------------------------------------------------------------------

  @Test
  void aStageIsSavedOnceRetriesReplayAndChangesAreRefused() {
    startRound1();
    UUID key = key();
    long revision = revision();
    Response first = expectOk(submitStage(alpha, key, 4, "{\"k1\":\"Van\",\"k5\":\"Van\",\"reliable\":1}"));
    Response retry = expectOk(submitStage(alpha, key, 4, "{\"k1\":\"Van\",\"k5\":\"Van\",\"reliable\":1}"));
    assertEquals(first.body(), retry.body(), "the same key and body give the same receipt");
    expectError(submitStage(alpha, key, 4, PERFECT.get(3)), 409, "This Idempotency-Key was already used for a different request.");
    expectError(submitStage(alpha, key(), 4, PERFECT.get(3)), 409, SAVED);
    expectError(submitStage(alpha, key(), 4, "{\"k1\":\"Van\",\"k5\":\"Van\",\"reliable\":1}"), 409, SAVED);
    assertEquals(1, count("SELECT count(*) FROM sentinel_submission WHERE team_id = " + alpha.id()));
    assertEquals(new BigDecimal("4.00"), db.queryForObject("SELECT points_earned FROM sentinel_submission WHERE team_id = ?", BigDecimal.class, alpha.id()), "the first answer is the one that counts (k=5 right, k=1 and the trust wrong)");
    assertEquals(revision, revision(), "saving a stage does not bump the global revision");

    expectError(submitStage(alpha, key(), 5, "{\"config\":\"x\"}"), 400, "Unexpected field config.");
    expectError(submitStage(alpha, key(), 5, "[]"), 400, "Send the stage's answer as a JSON object.");
    expectError(submitStage(alpha, key(), 5, "{\"tp\":1}"), 400, "Enter fp as a whole number.");
    expectError(submitStage(alpha, key(), 3, "{\"config\":\"nope\"}"), 400, "Choose one of the stage's configurations.");
    expectError(submitStage(alpha, key(), 2, "{\"choices\":{\"entry-gate\":\"USE\"}}"), 400, "Decide every feature: use it or drop it.");
    expectError(submitStage(alpha, key(), 11, PERFECT.get(0)), 404, "Stage not found.");
    assertEquals(1, count("SELECT count(*) FROM sentinel_submission WHERE team_id = " + alpha.id()), "a refused answer locks nothing");
    expectOk(submitStage(alpha, key(), 5, PERFECT.get(4)));
    assertEquals(400, call(org.springframework.http.HttpMethod.POST, "/team/rooms/" + ai + "/activity/stages/5/submit", alpha.token(), PERFECT.get(4), null).status());

    Response view = expectOk(view(alpha));
    assertEquals(2, (int) view.json("$.savedCount"));
    assertEquals(List.of(true), view.json("$.missions[1].stages[?(@.number == 4)].saved"));
  }

  // ---- concurrency --------------------------------------------------------------------------------

  @Test
  void runBudgetsHoldWhenManyRequestsArriveTogether() throws Exception {
    startRound1();
    List<String> configs = new ArrayList<>();
    for (String config : List.of("knn-1", "knn-5", "knn-15", "tree-2", "tree-5", "tree-20")) {
      configs.add(config);
      configs.add(config);
    }
    List<Response> responses = parallel(configs.size(), index -> run(alpha, key(), 3, configs.get(index)));
    Set<String> succeeded = new HashSet<>();
    for (int i = 0; i < responses.size(); i++) {
      Response response = responses.get(i);
      if (response.status() == 200) succeeded.add(configs.get(i));
      else expectError(response, 409, "You have used all the experiments of this stage.");
    }
    assertEquals(4, succeeded.size(), "exactly the budget of distinct configurations succeeded: " + succeeded);
    assertEquals(4, count("SELECT count(*) FROM sentinel_run WHERE team_id = " + alpha.id() + " AND stage = 3"));
    assertEquals(succeeded, new HashSet<>(db.queryForList("SELECT config FROM sentinel_run WHERE team_id = ?", String.class, alpha.id())));

    List<String> rag = List.of("small-1", "small-3", "large-1", "large-3", "small-1", "large-3");
    List<Response> ragResponses = parallel(rag.size(), index -> run(alfa, key(), 10, rag.get(index)));
    Set<String> ragSucceeded = new HashSet<>();
    for (int i = 0; i < ragResponses.size(); i++) {
      if (ragResponses.get(i).status() == 200) ragSucceeded.add(rag.get(i));
      else expectError(ragResponses.get(i), 409, "You have used all the experiments of this stage.");
    }
    assertEquals(3, ragSucceeded.size(), "RAG Fix allows three different runs: " + ragSucceeded);
    assertEquals(ragSucceeded, new HashSet<>(db.queryForList("SELECT config FROM sentinel_run WHERE team_id = ? AND stage = 10", String.class, alfa.id())));
  }

  @Test
  void manySubmitsOfOneStageSaveItOnceWhetherTheKeyIsSharedOrNot() throws Exception {
    startRound1();
    UUID shared = key();
    List<Response> sameKey = parallel(8, index -> submitStage(alpha, shared, 6, PERFECT.get(5)));
    for (Response response : sameKey) expectOk(response);
    assertEquals(1, sameKey.stream().map(Response::body).distinct().count(), "every retry got the same receipt");
    assertEquals(1, count("SELECT count(*) FROM sentinel_submission WHERE team_id = " + alpha.id()));

    List<Response> otherKeys = parallel(8, index -> submitStage(alfa, key(), 6, PERFECT.get(5)));
    assertEquals(1, otherKeys.stream().filter(response -> response.status() == 200).count());
    otherKeys.stream().filter(response -> response.status() != 200).forEach(response -> expectError(response, 409, SAVED));
    assertEquals(1, count("SELECT count(*) FROM sentinel_submission WHERE team_id = " + alfa.id()));
  }

  @Test
  void theLastSubmitAndTheRoundEndRacingEvaluateEachTeamOnceWithOneClue() throws Exception {
    startRound1();
    TestTeam ace = team("Ace", GroupCode.A);
    List<TestTeam> racers = List.of(alpha, alfa, ace);
    for (TestTeam racer : racers) saveStages(racer, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<Response>> submits = new ArrayList<>();
      for (TestTeam racer : racers) {
        submits.add(pool.submit(() -> {
          go.await();
          return submitStage(racer, key(), 10, PERFECT.get(9));
        }));
      }
      Future<Response> end = pool.submit(() -> {
        go.await();
        return post("/admin/round/timer/end", admin, null);
      });
      go.countDown();
      for (int i = 0; i < racers.size(); i++) {
        Response submitted = submits.get(i).get(30, TimeUnit.SECONDS);
        TestTeam racer = racers.get(i);
        String cause = db.queryForObject("SELECT cause FROM sentinel_evaluation WHERE team_id = ?", String.class, racer.id());
        BigDecimal total = db.queryForObject("SELECT total FROM sentinel_evaluation WHERE team_id = ?", BigDecimal.class, racer.id());
        if (submitted.status() == 200) {
          assertEquals("LAST_STAGE", cause, "the submit won: 100 points");
          assertEquals(new BigDecimal("100.00"), total);
        } else {
          expectError(submitted, 409, "Time is up for this round.");
          assertEquals("TIME_UP", cause, "the round end won: nine stages, 90 points");
          assertEquals(new BigDecimal("90.00"), total);
        }
      }
      expectOk(end.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
    expectOk(post("/admin/round/timer/end", admin, null));
    setRound(2);
    for (TestTeam racer : racers) {
      assertEquals(1, count("SELECT count(*) FROM sentinel_evaluation WHERE team_id = " + racer.id()), racer.name());
      assertEquals(1, count("SELECT count(*) FROM room_submission WHERE team_id = " + racer.id()), racer.name());
      assertEquals(1, count("SELECT count(*) FROM collected_clue WHERE team_id = " + racer.id()), "the clue is collected once for " + racer.name());
      assertEquals(1, count("SELECT count(*) FROM audit_event WHERE action = 'EVENT_MISSION_EVALUATED' AND target LIKE '% team:" + racer.id() + " %'"));
    }
  }

  // ---- timing and access --------------------------------------------------------------------------

  @Test
  void nothingIsAcceptedBeforeTheTimerWhilePausedAtTimeUpOrAfterTheResult() {
    assignRound(1);
    openRoom(RoomCode.AI);
    String notStarted = "The timer has not started yet.";
    expectError(run(alpha, key(), 3, "knn-1"), 409, notStarted);
    expectError(submitStage(alpha, key(), 1, PERFECT.get(0)), 409, notStarted);

    startTimer();
    closeRoom(RoomCode.AI);
    expectError(run(alpha, key(), 3, "knn-1"), 409, "This room is closed.");
    expectError(submitStage(alpha, key(), 1, PERFECT.get(0)), 409, "This room is closed.");
    openRoom(RoomCode.AI);
    expectError(
      submit(alpha, RoomCode.AI, key(), "output", AI_KEY),
      409,
      "This room records its result from its activity; there is no answer form."
    );

    expectOk(post("/admin/round/timer/pause", admin, null));
    expectError(run(alpha, key(), 3, "knn-1"), 409, "The round is paused.");
    expectError(submitStage(alpha, key(), 1, PERFECT.get(0)), 409, "The round is paused.");
    expectOk(post("/admin/round/timer/resume", admin, null));
    expectOk(submitStage(alpha, key(), 1, PERFECT.get(0)));

    expectOk(post("/admin/teams/" + alfa.id() + "/disqualify", admin, "{\"reason\":\"Left the event early\"}"));
    expectError(submitStage(alfa, key(), 1, PERFECT.get(0)), 409, "Your team has been disqualified. Contact the organizer.");

    clock.advance(Duration.ofMinutes(5).plusSeconds(1));
    expectError(run(alpha, key(), 3, "knn-1"), 409, "Time is up for this round.");
    expectError(submitStage(alpha, key(), 2, PERFECT.get(1)), 409, "Time is up for this round.");
    expectOk(post("/admin/round/timer/end", admin, null));
    expectError(submitStage(alpha, key(), 2, PERFECT.get(1)), 409, "Time is up for this round.");

    setRound(2);
    expectError(view(alpha), 404, NO_ACTIVITY);
    expectError(run(alpha, key(), 3, "knn-1"), 404, NO_ACTIVITY);
    assertEquals(0, count("SELECT count(*) FROM sentinel_run"));
    assertEquals(1, count("SELECT count(*) FROM sentinel_submission"));
  }

  @Test
  void afterTheResultNothingIsAcceptedAndAnotherRoundOrTeamIsNotFound() {
    startRound1();
    saveStages(alpha, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    expectError(run(alpha, key(), 3, "knn-1"), 409, EVALUATED);
    expectError(submitStage(alpha, key(), 10, PERFECT.get(9)), 409, EVALUATED);

    TestTeam bravo = team("Bravo", GroupCode.B);
    expectError(view(bravo), 404, NO_ACTIVITY);
    expectError(run(bravo, key(), 3, "knn-1"), 404, NO_ACTIVITY);
    expectError(submitStage(bravo, key(), 1, PERFECT.get(0)), 404, NO_ACTIVITY);
    long linux = rooms.get(RoomCode.LINUX);
    expectError(get("/team/rooms/" + linux + "/activity", alpha.token()), 404, NO_ACTIVITY);
    expectError(get("/team/rooms/999999/activity", alpha.token()), 404, NO_ACTIVITY);
    expectError(teamPost(alpha, "/team/rooms/" + linux + "/activity/stages/3/runs", key(), "{\"config\":\"knn-1\"}"), 404, NO_ACTIVITY);
    expectOk(post("/admin/round/timer/end", admin, null));
    setRound(4);
    openRoom(RoomCode.AI);
    assertEquals("RUNNING", expectOk(view(bravo)).json("$.phase"), "group B is in the AI room in round 4");
    expectOk(run(bravo, key(), 3, "knn-1"));
  }

  @Test
  void otherTeamsRoomsAndManagersSeeNothing() {
    startRound1();
    assertEquals(401, call(org.springframework.http.HttpMethod.GET, "/team/rooms/" + ai + "/activity", null, null, null).status());
    expectError(view(alpha, managers.get(RoomCode.AI)), 403, "A team account is required.");
    expectError(view(alpha, admin), 403, "A team account is required.");
    for (String path : List.of("/mission", "/mission/results", "/mission/export", "/mission/results.csv")) {
      expectError(get("/rooms/" + ai + path, managers.get(RoomCode.LINUX)), 404, "Room not found.");
      expectError(get("/rooms/" + ai + path, alpha.token()), 403, "Access denied.");
      assertEquals(401, get("/rooms/" + ai + path, null).status());
      expectOk(get("/rooms/" + ai + path, managers.get(RoomCode.AI)));
      expectOk(get("/rooms/" + ai + path, admin));
    }
    expectError(importMission(SentinelFixture.shipped(), managers.get(RoomCode.LINUX)), 404, "Room not found.");
    expectError(put("/rooms/" + ai + "/mission/settings", managers.get(RoomCode.LINUX), "{\"passScore\":10}"), 404, "Room not found.");
    expectError(importMission(SentinelFixture.shipped(), alpha.token()), 403, "Access denied.");
    expectError(put("/rooms/" + ai + "/mission/settings", alpha.token(), "{\"passScore\":10}"), 403, "Access denied.");
    expectError(setActivity(ai, "null", managers.get(RoomCode.AI)), 403, "Access denied.");
    expectError(get("/rooms/999999/mission", admin), 404, "Room not found.");
    expectOk(setActivity(ai, "null", admin));
    expectError(view(alpha), 404, NO_ACTIVITY);
    expectError(importMission(SentinelFixture.shipped()), 409, "The AI mission is not enabled for this room.");
  }

  // ---- evaluation ---------------------------------------------------------------------------------

  @Test
  void aTotalEqualToThePassScorePassesAndJustBelowDoesNot() {
    startRound1();
    // Alpha: stages 1 to 6 are perfect: exactly 60 points.
    saveStages(alpha, 1, 2, 3, 4, 5, 6);
    // Alfa: one feature wrong in stage 2 (8.33) and the same five other stages: 58.33.
    expectOk(submitStage(alfa, key(), 2, PERFECT.get(1).replace("\"plate-number\":\"DROP\"", "\"plate-number\":\"USE\"")));
    saveStages(alfa, 1, 3, 4, 5, 6);
    assertEquals(0, count("SELECT count(*) FROM sentinel_evaluation"), "no evaluation before the last stage or Time's up");
    long before = revision();
    expectOk(post("/admin/round/timer/end", admin, null));
    assertTrue(revision() > before, "the evaluation is announced to every screen");

    assertEquals(new BigDecimal("60.00"), evaluationTotal(alpha));
    assertEquals(new BigDecimal("58.33"), evaluationTotal(alfa));
    assertEquals(List.of("TIME_UP"), db.queryForList("SELECT DISTINCT cause FROM sentinel_evaluation", String.class));
    assertEquals(60, count("SELECT pass_score FROM sentinel_evaluation WHERE team_id = " + alpha.id()));

    Response passed = expectOk(view(alpha));
    assertEquals(true, passed.json("$.result.clueUnlocked"));
    assertEquals(AI_KEY, passed.json("$.result.clue"));
    Response failed = expectOk(view(alfa));
    assertEquals(false, failed.json("$.result.clueUnlocked"));
    assertNull(failed.json("$.result.clue"));

    // Recorded through the room-submission path: a CORRECT submission with its verdict and a ROOM clue.
    assertEquals(true, db.queryForObject("SELECT clue_correct FROM room_submission WHERE team_id = ?", Boolean.class, alpha.id()));
    assertEquals(false, db.queryForObject("SELECT clue_correct FROM room_submission WHERE team_id = ?", Boolean.class, alfa.id()));
    assertEquals("ROOM", db.queryForObject("SELECT source FROM collected_clue WHERE team_id = ? AND room_id = ?", String.class, alpha.id(), ai));
    assertEquals(0, count("SELECT count(*) FROM collected_clue WHERE team_id = " + alfa.id()));
    assertEquals("AI mission · stages 6/10 submitted", db.queryForObject("SELECT technical_output FROM room_submission WHERE team_id = ?", String.class, alpha.id()));
    assertEquals("AI mission result", db.queryForObject("SELECT clue_answer FROM room_submission WHERE team_id = ?", String.class, alpha.id()));

    Response board = expectOk(get("/team/board", alpha.token()));
    assertEquals(List.of(AI_KEY), board.json("$.evidence[?(@.roomCode == 'AI')].clueValue"));
    assertEquals(List.of(true), board.json("$.evidence[?(@.roomCode == 'AI')].resultRecorded"));
    assertEquals(1, (int) board.json("$.cluesCollected"));
    assertEquals(List.of(), expectOk(get("/team/board", alfa.token())).json("$.evidence[?(@.roomCode == 'AI' && @.clueValue != null)]"));
  }

  @Test
  void theLastStageEvaluatesAtOnceAndTheRoundEndDoesNotEvaluateAgain() {
    startRound1();
    saveStages(alpha, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    assertEquals(0, count("SELECT count(*) FROM sentinel_evaluation"));
    long before = revision();
    saveStages(alpha, 10);
    assertTrue(revision() > before);
    assertEquals(new BigDecimal("100.00"), evaluationTotal(alpha));
    assertEquals("LAST_STAGE", db.queryForObject("SELECT cause FROM sentinel_evaluation WHERE team_id = ?", String.class, alpha.id()));
    expectOk(post("/admin/round/timer/end", admin, null));
    setRound(2);
    assertEquals(1, count("SELECT count(*) FROM sentinel_evaluation WHERE team_id = " + alpha.id()));
    assertEquals(1, count("SELECT count(*) FROM collected_clue WHERE team_id = " + alpha.id()));
  }

  @Test
  void theRoundEndEvaluatesEveryUnfinishedTeamOfTheRoomOnceWithUnsavedStagesAtZero() {
    startRound1();
    TestTeam bravo = team("Bravo", GroupCode.B);
    TestTeam out = team("Delta", GroupCode.A);
    expectOk(post("/admin/teams/" + out.id() + "/disqualify", admin, "{\"reason\":\"Left the event early\"}"));
    saveStages(alpha, 1, 2);
    assertEquals(0, count("SELECT count(*) FROM sentinel_evaluation"));

    long before = revision();
    expectOk(post("/admin/round/timer/end", admin, null));
    assertTrue(revision() > before);
    assertEquals(
      List.of("Alfa TIME_UP false 0.00", "Alpha TIME_UP false 20.00"),
      db.queryForList(
        "SELECT t.name || ' ' || e.cause || ' ' || e.passed || ' ' || e.total FROM sentinel_evaluation e JOIN event_team t ON t.id = e.team_id ORDER BY t.name",
        String.class
      ),
      "Bravo is in another room and Delta is disqualified"
    );
    assertEquals(0, count("SELECT count(*) FROM sentinel_evaluation WHERE team_id = " + bravo.id()));
    assertEquals("AI mission · stages 2/10 submitted", db.queryForObject("SELECT technical_output FROM room_submission WHERE team_id = ?", String.class, alpha.id()));
    long after = revision();
    expectOk(post("/admin/round/timer/end", admin, null));
    assertEquals(after, revision(), "ending again changes nothing");
    assertEquals(2, count("SELECT count(*) FROM sentinel_evaluation"));
  }

  @Test
  void theNextRoundEvaluatesATimerThatRanOutByItselfButNotAnUnstartedRound() {
    assignRound(1);
    setRound(2);
    assertEquals(0, count("SELECT count(*) FROM sentinel_evaluation"), "a round whose timer never started is not evaluated");

    TestTeam bravo = team("Bravo", GroupCode.B);
    assignRound(4);
    startTimer();
    openRoom(RoomCode.AI);
    expectOk(submitStage(bravo, key(), 1, PERFECT.get(0)));
    clock.advance(Duration.ofMinutes(5).plusSeconds(1));
    assertNull(expectOk(view(bravo)).json("$.result"), "no stored transition yet: the result waits for the round's end");
    expectOk(post("/admin/round", admin, "{\"round\":5}"));
    assertEquals("TIME_UP", db.queryForObject("SELECT cause FROM sentinel_evaluation WHERE team_id = ?", String.class, bravo.id()));
    assertEquals(new BigDecimal("10.00"), evaluationTotal(bravo));
  }

  @Test
  void aManualVerdictOverrideStillDecidesTheClueAndKeyChangesKeepTheResult() {
    startRound1();
    saveStages(alpha, 1, 2, 3, 4, 5, 6);
    expectOk(post("/admin/round/timer/end", admin, null));
    String manager = managers.get(RoomCode.AI);

    expectOk(override(evaluationSubmission(alpha), "REJECT", manager));
    assertEquals(0, count("SELECT count(*) FROM collected_clue WHERE team_id = " + alpha.id()));
    assertEquals(false, expectOk(view(alpha)).json("$.result.clueUnlocked"));
    expectOk(override(evaluationSubmission(alfa), "ACCEPT", admin));
    Response granted = expectOk(view(alfa));
    assertEquals(true, granted.json("$.result.clueUnlocked"));
    assertEquals(AI_KEY, granted.json("$.result.clue"));

    expectOk(setClueKey(RoomCode.AI, "BLACK VAN"));
    assertEquals("BLACK VAN", expectOk(view(alfa)).json("$.result.clue"), "the clue follows the current key");
    assertEquals(1, count("SELECT count(*) FROM collected_clue WHERE team_id = " + alfa.id()), "a key change never re-checks a mission result");

    Response results = expectOk(get("/rooms/" + ai + "/mission/results", manager));
    assertEquals(List.of("PASSED"), results.json("$.teams[?(@.teamName == 'Alpha')].status"));
    assertEquals(List.of(false), results.json("$.teams[?(@.teamName == 'Alpha')].clueGranted"));
    assertEquals(List.of("NOT_PASSED"), results.json("$.teams[?(@.teamName == 'Alfa')].status"));
    assertEquals(List.of(true), results.json("$.teams[?(@.teamName == 'Alfa')].clueGranted"));
  }

  @Test
  void thePassScoreIsEditableUntilTheFirstEvaluationAndThenLocked() {
    String manager = managers.get(RoomCode.AI);
    Response editor = setPassScore(55);
    assertEquals(55, (int) editor.json("$.passScore"));
    assertEquals(false, editor.json("$.passScoreLocked"));
    assertEquals(400, put("/rooms/" + ai + "/mission/settings", manager, "{\"passScore\":101}").status());
    assertEquals(400, put("/rooms/" + ai + "/mission/settings", manager, "{\"passScore\":-1}").status());
    assertEquals(400, put("/rooms/" + ai + "/mission/settings", manager, "{}").status());
    assertEquals(55, (int) expectOk(get("/rooms/" + ai + "/mission/export", manager)).json("$.passScore"));

    startRound1();
    expectOk(post("/admin/round/timer/end", admin, null));
    assertEquals(new BigDecimal("0.00"), evaluationTotal(alpha), "nobody saved anything: every stage is worth 0");
    assertEquals(false, db.queryForObject("SELECT passed FROM sentinel_evaluation WHERE team_id = ?", Boolean.class, alpha.id()), "0 < 55");
    String locked = "A team in this room has already been evaluated; the pass score can no longer change.";
    expectError(put("/rooms/" + ai + "/mission/settings", manager, "{\"passScore\":50}"), 409, locked);
    Response same = expectOk(put("/rooms/" + ai + "/mission/settings", manager, "{\"passScore\":55}"));
    assertEquals(true, same.json("$.passScoreLocked"));
    String other = SentinelFixture.shipped().replaceFirst("\"passScore\": 60", "\"passScore\": 40");
    assertTrue(other.contains("\"passScore\": 40"));
    expectError(importMission(other), 409, locked);
    assertEquals(List.of(55), db.queryForList("SELECT DISTINCT pass_score FROM sentinel_evaluation", Integer.class));
  }

  // ---- content ------------------------------------------------------------------------------------

  @Test
  void importAndExportRoundTripAndBadFilesAreRefusedWholeWithEveryProblemListed() throws Exception {
    String manager = managers.get(RoomCode.AI);
    Response exported = expectOk(get("/rooms/" + ai + "/mission/export", manager));
    JsonNode expected = json.readTree(SentinelFixture.shipped());
    JsonNode actual = json.readTree(exported.body());
    assertEquals(1, actual.get("format").asInt());
    assertEquals(60, actual.get("passScore").asInt());
    assertEquals(expected.get("story"), actual.get("story"));
    assertEquals(expected.get("missions").size(), actual.get("missions").size());
    for (int m = 0; m < 3; m++) {
      assertEquals(expected.get("missions").get(m).get("intro"), actual.get("missions").get(m).get("intro"));
      assertEquals(expected.get("missions").get(m).get("stages"), actual.get("missions").get(m).get("stages"), "mission " + m);
    }
    expectOk(importMission(exported.body()));
    assertEquals(exported.body(), expectOk(get("/rooms/" + ai + "/mission/export", manager)).body(), "export → import → export is stable");

    Response editor = expectOk(get("/rooms/" + ai + "/mission", manager));
    assertEquals(true, editor.json("$.hasContent"));
    assertEquals(10, (int) editor.json("$.stages.length()"));
    assertEquals(100, ((Number) editor.json("$.totalPoints")).intValue());
    assertEquals(List.of("predict", "predict", "predict", "see", "see", "see", "noor", "noor", "noor", "noor"), editor.json("$.stages[*].mission"));
    assertEquals(false, editor.json("$.workStarted"));
    assertEquals(true, editor.json("$.editable"));

    // A bad file: nothing is saved, and every problem comes back at once.
    ObjectNode bad = (ObjectNode) json.readTree(SentinelFixture.shipped());
    ((ObjectNode) bad.get("missions").get(0).get("stages").get(0)).put("maxPoints", 11);
    ((ObjectNode) bad.get("story")).put("failed", "You scored 59.");
    ((ObjectNode) bad.get("missions").get(1).get("stages").get(0).get("vehicles").get(11)).put("kind", "Van");
    Response rejected = importMission(json.writeValueAsString(bad));
    assertEquals(400, rejected.status(), rejected.body());
    List<String> problems = rejected.json("$.problems");
    assertTrue(problems.contains("story.failed must not contain a number."), problems.toString());
    assertTrue(problems.contains("The stages are worth 101 points in all; they must add up to 100."), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("Stage 4: The nearest vehicle must be an outlier")), problems.toString());
    assertTrue(((String) rejected.json("$.detail")).startsWith("The file has "), rejected.body());
    ((ObjectNode) bad.get("missions").get(0)).remove("title");
    List<String> shape = importMission(json.writeValueAsString(bad)).json("$.problems");
    assertEquals(List.of("Mission 1 · title: must be a non-empty text."), shape, "shape problems come first");
    assertEquals(exported.body(), expectOk(get("/rooms/" + ai + "/mission/export", manager)).body(), "a refused import changes nothing");

    assertEquals(List.of("The file is not valid JSON."), importMission("{{{").json("$.problems"));
    assertEquals(List.of("The file must be a JSON object."), importMission("[]").json("$.problems"));
    assertEquals(List.of("The file is larger than 400 KB."), importMission("x".repeat(400_001)).json("$.problems"));
    assertTrue((int) importMission("{}").json("$.problems.length()") >= 3);

    List<String> audits = db.queryForList("SELECT action || ' ' || target FROM audit_event WHERE action LIKE 'EVENT_MISSION%' OR action = 'EVENT_ROOM_ACTIVITY'", String.class);
    assertTrue(audits.contains("EVENT_MISSION_IMPORTED room:" + ai + " stages:10"), audits.toString());
    assertFalse(audits.toString().contains("Rami") || audits.toString().contains("SENTINEL"), "audits never carry the content: " + audits);
  }

  @Test
  void theMissionIsLockedOnceAnyTeamRanOrSavedAnythingAndAfterTheFinalOpens() {
    startRound1();
    expectOk(importMission(SentinelFixture.shipped()));
    expectOk(run(alpha, key(), 1, "z-3"));
    String locked = "Teams have already worked in this room; its mission can no longer be replaced.";
    expectError(importMission(SentinelFixture.shipped()), 409, locked);
    assertEquals(true, expectOk(get("/rooms/" + ai + "/mission", managers.get(RoomCode.AI))).json("$.workStarted"));
    expectOk(get("/rooms/" + ai + "/mission/export", managers.get(RoomCode.AI)));
    assertEquals(0, count("SELECT count(*) FROM sentinel_submission"));

    openFinal();
    expectError(importMission(SentinelFixture.shipped()), 409, "Event content is read-only after the final opens.");
  }

  @Test
  void aRoomWithoutMissionContentOrClueKeyBlocksTheTimerUnlessStartedAnyway() {
    assertEquals(List.of(), expectOk(get("/admin/board", admin)).json("$.startBlockers"));
    long linux = rooms.get(RoomCode.LINUX);
    expectOk(setActivity(linux, "\"AI_QUIZ\"", admin));
    db.update("UPDATE event_room SET clue_key = '' WHERE id = ?", ai);

    Response board = expectOk(get("/admin/board", admin));
    List<String> blocking = board.json("$.alerts[?(@.blocking == true)].message");
    assertEquals(
      List.of(
        "Room AI runs the AI mission but has no clue key, so passing teams would get no clue.",
        "Room Linux runs the AI mission but has no mission content."
      ),
      blocking
    );
    assertEquals(blocking, board.json("$.startBlockers"));
    assertEquals(List.of("/event/room/" + linux + "/mission"), board.json("$.alerts[?(@.code == 'MISSION_WITHOUT_CONTENT')].link"));
    assertEquals(List.of(), board.json("$.alerts[?(@.code == 'QUIZ_PLAN')]"), "there is no planning warning any more: the file must add up to 100");

    assignRound(1);
    expectError(
      post("/admin/round/timer/start", admin, null),
      409,
      "The AI mission is not ready: " + String.join(" ", blocking) + " Press Start anyway to start regardless."
    );
    assertEquals("ASSIGNED", expectOk(get("/admin/board", admin)).json("$.state.phase"));
    expectOk(post("/admin/round/timer/start", admin, "{\"anyway\":true}"));
    assertEquals("RUNNING", expectOk(get("/admin/board", admin)).json("$.state.phase"));
    assertEquals(1, count("SELECT count(*) FROM audit_event WHERE action = 'EVENT_TIMER_STARTED' AND target = 'round:1 anyway'"));
  }

  @Test
  void theAdminSwitchesTheActivityAndRepeatingItChangesNothing() {
    assignRound(1);
    assertEquals("AI_QUIZ", expectOk(get("/team/board", alpha.token())).json("$.scheduledRoom.activity"));
    assertEquals("AI_QUIZ", expectOk(get("/rooms/" + ai + "/board", managers.get(RoomCode.AI))).json("$.room.activity"));
    long before = revision();
    expectOk(setActivity(ai, "null", admin));
    assertTrue(revision() > before);
    long unchanged = revision();
    expectOk(setActivity(ai, "null", admin));
    assertEquals(unchanged, revision(), "repeating the same setting is a no-op");
    assertNull(expectOk(get("/team/board", alpha.token())).json("$.scheduledRoom.activity"));
    assertEquals(List.of(), expectOk(get("/admin/board", admin)).json("$.alerts[?(@.code =~ /MISSION.*/)]"));
  }

  // ---- reset, results, score ----------------------------------------------------------------------

  @Test
  void resetsDeleteATeamsMissionWorkAfterASnapshotThatKeepsIt() {
    startRound1();
    expectOk(run(alpha, key(), 3, "knn-5"));
    saveStages(alpha, 1, 2);
    saveStages(alfa, 1);
    expectOk(post("/admin/round/timer/end", admin, null));
    Response reset = expectOk(post("/admin/reset/team/" + alpha.id(), admin, "{\"reason\":\"Mission reset test\"}"));
    long snapshot = ((Number) reset.json("$.snapshotId")).longValue();
    for (String table : List.of("sentinel_run", "sentinel_submission", "sentinel_evaluation")) {
      assertEquals(0, count("SELECT count(*) FROM " + table + " WHERE team_id = " + alpha.id()), table);
    }
    assertEquals(1, count("SELECT count(*) FROM sentinel_submission WHERE team_id = " + alfa.id()));
    assertEquals(1, count("SELECT count(*) FROM sentinel_evaluation WHERE team_id = " + alfa.id()));
    String data = db.queryForObject("SELECT data::text FROM event_snapshot WHERE id = ?", String.class, snapshot);
    for (String table : List.of("sentinel_run", "sentinel_submission", "sentinel_evaluation")) {
      assertTrue(data.contains("\"" + table + "\": [{"), table + " in " + data);
    }
    assertTrue(count("SELECT count(*) FROM sentinel_content") == 1, "the mission stays: it is content");

    String body = "{\"confirmText\":\"RESET\",\"password\":\"%s\",\"reason\":\"Mission event reset\"}".formatted(PASSWORD);
    Response all = expectOk(post("/admin/reset/event", admin, body));
    String eventData = db.queryForObject("SELECT data::text FROM event_snapshot WHERE id = ?", String.class, ((Number) all.json("$.snapshotId")).longValue());
    assertTrue(eventData.contains("\"sentinel_submission\": [{"), eventData);
    for (String table : List.of("sentinel_run", "sentinel_submission", "sentinel_evaluation")) assertEquals(0, count("SELECT count(*) FROM " + table), table);
    assertEquals(1, count("SELECT count(*) FROM sentinel_content"));
    assertEquals(60, (int) expectOk(get("/rooms/" + ai + "/mission/export", managers.get(RoomCode.AI))).json("$.passScore"));
  }

  @Test
  void resultsRankTeamsByTotalThenEarlierAndTheCsvNeutralizesFormulas() {
    startRound1();
    // Alpha: 10 + 10; Alfa reaches the same 20 five seconds later; Ace 6 (iqr-3) only; a run-only team appears with 0.
    saveStages(alpha, 1, 2);
    clock.advance(Duration.ofSeconds(5));
    saveStages(alfa, 1, 2);
    TestTeam ace = team("Ace", GroupCode.A);
    expectOk(submitStage(ace, key(), 1, "{\"config\":\"iqr-3\"}"));
    TestTeam formula = team("=HYPERLINK(1)", GroupCode.A);
    expectOk(run(formula, key(), 1, "z-2"));

    Response results = expectOk(get("/rooms/" + ai + "/mission/results", managers.get(RoomCode.AI)));
    assertEquals(List.of("Alpha", "Alfa", "Ace", "=HYPERLINK(1)"), results.json("$.teams[*].teamName"));
    assertEquals(List.of(1, 2, 3, 4), results.json("$.teams[*].rank"));
    assertEquals(20.0, ((Number) results.json("$.teams[0].total")).doubleValue());
    assertEquals(6.0, ((Number) results.json("$.teams[2].total")).doubleValue());
    assertEquals(0.0, ((Number) results.json("$.teams[3].total")).doubleValue());
    assertEquals(10.0, ((Number) results.json("$.teams[0].stagePoints[0]")).doubleValue());
    assertEquals(10.0, ((Number) results.json("$.teams[0].stagePoints[1]")).doubleValue());
    assertNull(((List<?>) results.json("$.teams[0].stagePoints")).get(2), "an unsaved stage has no points");
    assertEquals(100, (int) results.json("$.maxPoints"));
    assertEquals(60, (int) results.json("$.passScore"));
    assertEquals(false, results.json("$.passScoreLocked"));
    assertEquals(10, (int) results.json("$.stages.length()"));
    assertEquals("Outlier Hunt", results.json("$.stages[0].title"));
    assertEquals(List.of("IN_PROGRESS", "IN_PROGRESS", "IN_PROGRESS", "IN_PROGRESS"), results.json("$.teams[*].status"));

    expectOk(post("/admin/round/timer/end", admin, null));
    String csv = expectOk(get("/rooms/" + ai + "/mission/results.csv", managers.get(RoomCode.AI))).body();
    String[] lines = csv.split("\r\n");
    assertEquals("rank,team,group,stage1,stage2,stage3,stage4,stage5,stage6,stage7,stage8,stage9,stage10,total,passScore,status,clueGranted,stagesSaved,lastSavedAt,evaluatedAt", lines[0]);
    assertTrue(lines[1].startsWith("1,\"Alpha\",A,10.00,10.00,,,,,,,,,20.00,60,NOT_PASSED,no,2,"), lines[1]);
    assertTrue(lines[3].startsWith("3,\"Ace\",A,6.00,,,,,,,,,,6.00,60,NOT_PASSED,no,1,"), lines[3]);
    assertTrue(csv.contains("\"'=HYPERLINK(1)\""), csv);
    assertEquals(5, lines.length);
  }

  @Test
  void theRoomScoreIsAReadOnlySumOutOf100() {
    startRound1();
    saveStages(alpha, 1, 2);
    expectOk(run(alfa, key(), 3, "knn-1"));
    long revision = revision();
    Optional<SentinelScoreService.RoomScore> score = scoreService.roomScore(alpha.id());
    assertEquals(new BigDecimal("20.00"), score.orElseThrow().points());
    assertEquals(100, score.orElseThrow().maximum());
    assertEquals(0, scoreService.roomScore(alfa.id()).orElseThrow().points().signum(), "a run earns nothing");
    saveStages(alpha, 3, 4, 5, 6, 7, 8, 9, 10);
    assertEquals(new BigDecimal("100.00"), scoreService.roomScore(alpha.id()).orElseThrow().points());
    assertTrue(revision() > revision, "only the evaluation moved the revision; the score service wrote nothing");
    long afterEvaluation = revision();
    scoreService.roomScore(alpha.id());
    assertEquals(afterEvaluation, revision());
    expectOk(setActivity(ai, "null", admin));
    assertTrue(scoreService.roomScore(alpha.id()).isEmpty(), "no room runs the mission");
  }

  // ---- statements ---------------------------------------------------------------------------------

  @Test
  void theTeamViewUsesTheSameEightStatementsForTwoAndForFourteenTeams() {
    startRound1();
    expectOk(run(alpha, key(), 3, "knn-5"));
    saveStages(alpha, 1, 2);
    expectOk(run(alfa, key(), 1, "iqr-3"));
    List<String> few = viewStatements();
    for (TestTeam team : teams(3)) {
      if (team.group() != GroupCode.A) continue;
      expectOk(run(team, key(), 3, "knn-1"));
      expectOk(run(team, key(), 3, "knn-5"));
      saveStages(team, 1, 2, 3);
    }
    saveStages(alfa, 4, 5, 6);
    assertEquals(14, count("SELECT count(*) FROM event_team"));
    List<String> many = viewStatements();
    assertEquals(8, few.size(), few.toString());
    assertEquals(few, many);
  }

  @Test
  void theResultsPageUsesTheSameStatementsForTwoAndForFourteenTeams() {
    startRound1();
    saveStages(alpha, 1, 2);
    expectOk(run(alfa, key(), 1, "iqr-3"));
    List<String> few = statementsOf(() -> get("/rooms/" + ai + "/mission/results", managers.get(RoomCode.AI)));
    for (TestTeam team : teams(3)) {
      if (team.group() != GroupCode.A) continue;
      expectOk(run(team, key(), 3, "knn-1"));
      saveStages(team, 1, 2, 3);
    }
    assertEquals(14, count("SELECT count(*) FROM event_team"));
    List<String> many = statementsOf(() -> get("/rooms/" + ai + "/mission/results", managers.get(RoomCode.AI)));
    assertEquals(few, many);
    assertEquals(6, few.size(), few.toString());
    assertEquals(5, (int) expectOk(get("/rooms/" + ai + "/mission/results", managers.get(RoomCode.AI))).json("$.teams.length()"));
  }

  // ---- helpers ------------------------------------------------------------------------------------

  private void startRound1() {
    setRound(1);
    openRoom(RoomCode.AI);
  }

  private static int intAt(Response response, String path) {
    return ((Number) response.json(path)).intValue();
  }

  private static UUID key() {
    return UUID.randomUUID();
  }

  private Response importMission(String body) {
    return importMission(body, managers.get(RoomCode.AI));
  }

  private Response importMission(String body, String token) {
    return post("/rooms/" + ai + "/mission/import", token, body);
  }

  private Response setPassScore(int passScore) {
    return expectOk(put("/rooms/" + ai + "/mission/settings", managers.get(RoomCode.AI), "{\"passScore\":" + passScore + "}"));
  }

  private Response setActivity(long roomId, String activity, String token) {
    return put("/admin/rooms/" + roomId + "/activity", token, "{\"activity\":" + activity + "}");
  }

  private Response view(TestTeam team) {
    return view(team, team.token());
  }

  private Response view(TestTeam team, String token) {
    return get("/team/rooms/" + ai + "/activity", token);
  }

  private Response run(TestTeam team, UUID key, int stage, String config) {
    return teamPost(team, "/team/rooms/" + ai + "/activity/stages/" + stage + "/runs", key, "{\"config\":\"" + config + "\"}");
  }

  private Response submitStage(TestTeam team, UUID key, int stage, String answerJson) {
    return teamPost(team, "/team/rooms/" + ai + "/activity/stages/" + stage + "/submit", key, answerJson);
  }

  /** Saves a perfect answer to each listed stage. */
  private void saveStages(TestTeam team, int... stages) {
    for (int stage : stages) expectOk(submitStage(team, key(), stage, PERFECT.get(stage - 1)));
  }

  private Response override(String submissionId, String verdict, String token) {
    return put("/rooms/" + ai + "/submissions/" + submissionId + "/clue-override", token, "{\"override\":\"" + verdict + "\",\"version\":1}");
  }

  private String evaluationSubmission(TestTeam team) {
    return db.queryForObject("SELECT submission_id::text FROM sentinel_evaluation WHERE team_id = ?", String.class, team.id());
  }

  private BigDecimal evaluationTotal(TestTeam team) {
    return db.queryForObject("SELECT total FROM sentinel_evaluation WHERE team_id = ?", BigDecimal.class, team.id());
  }

  private int count(String sql) {
    return db.queryForObject(sql, Integer.class);
  }

  private static int countOccurrences(String text, String part) {
    return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
  }

  /** Runs {@code count} calls at the same moment on separate threads and returns their responses in order. */
  private List<Response> parallel(int count, java.util.function.IntFunction<Response> call) throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(count);
    try {
      List<Future<Response>> futures = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        int index = i;
        futures.add(pool.submit(() -> {
          go.await();
          return call.apply(index);
        }));
      }
      go.countDown();
      List<Response> responses = new ArrayList<>();
      for (Future<Response> future : futures) responses.add(future.get(60, TimeUnit.SECONDS));
      return responses;
    } finally {
      pool.shutdownNow();
    }
  }

  /** A member JSON body never carries a key that names points, scores, correctness or an answer, at any depth. */
  private void assertNoSecrets(String body) {
    walk(json.readTree(body), body);
  }

  private void walk(JsonNode node, String body) {
    if (node.isObject()) {
      for (var entry : node.properties()) {
        assertFalse(FORBIDDEN_KEYS.contains(entry.getKey()), "member JSON has the key '" + entry.getKey() + "': " + body);
        walk(entry.getValue(), body);
      }
    } else if (node.isArray()) {
      node.forEach(child -> walk(child, body));
    }
  }

  /** JsonPath on a raw body (the team board is checked as text and as JSON). */
  private static final class JsonPathList {
    static <T> T read(String body, String path) {
      return com.jayway.jsonpath.JsonPath.read(body, path);
    }
  }

  /**
   * The SQL texts of one request without the request's session check (auth_session), consecutive repeats collapsed
   * (JdbcTemplate overloads call each other on the spy). The request is made once first to warm caches.
   */
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

  private List<String> viewStatements() {
    return statementsOf(() -> view(alpha));
  }
}
