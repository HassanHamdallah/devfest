package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.QuizScoring;
import dev.devfest.event.domain.RoomCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

/**
 * The AI room activity (rules §19) through the real API. Alpha, Alfa and Ace (group A) are in the AI room in
 * round 1; Bravo (group B) is in the Problem solving room. The quiz's MCQ part lasts 2 running minutes; rounds
 * last 5 minutes (well inside the access token lifetime). The quiz is worth 52 points; the pass score is 60 unless
 * a test sets it.
 */
@Import(ManualClock.Config.class)
class EventAiRoomTest extends EventIntegrationTest {

  private static final String EXPLANATION = "SECRET-EXPLANATION";
  private static final String AI_KEY = CLUE_KEYS.get(RoomCode.AI);
  private static final String EVALUATED = "Your team's result for this room is already recorded.";
  /** Six questions (one or two per section, 22 points) and three repairs of 4, 5 and 6 snippets (30 points). */
  private static final String QUIZ = """
    {"format":1,"mcqMinutes":2,
     "questions":[
      {"section":"EASY","prompt":"Which is a supervised learning task?","options":["Classification","Clustering","Dimensionality reduction"],"correctOption":0,"points":2,"explanation":"SECRET-EXPLANATION 1"},
      {"section":"EASY","prompt":"What does GPU stand for?","options":["General Processing Unit","Graphics Processing Unit"],"correctOption":1,"points":2,"explanation":"SECRET-EXPLANATION 2"},
      {"section":"MEDIUM","prompt":"Which layer reduces spatial size?","options":["Dense","Pooling","Embedding","Dropout"],"correctOption":1,"points":3,"explanation":""},
      {"section":"ADVANCED","prompt":"What does attention compute?","options":["Weighted sums of values","Sorted keys","Random masks"],"correctOption":0,"points":4},
      {"section":"LANDMARK","prompt":"Which year did AlexNet win ImageNet?","options":["2009","2012","2015","2018"],"correctOption":1,"points":5},
      {"section":"RECENT","prompt":"Which technique grounds answers in documents?","options":["Retrieval-augmented generation","Bagging"],"correctOption":0,"points":6}
     ],
     "repairs":[
      {"title":"Fix the training loop","instructions":"Order the lines.","points":10,"snippets":["for batch in data:","  optimizer.zero_grad()","  loss = model(batch)","  loss.backward()"]},
      {"title":"Fix the split","instructions":"Order the lines.","points":10,"snippets":["import random","rows = load()","random.shuffle(rows)","cut = int(len(rows) * 0.8)","train, test = rows[:cut], rows[cut:]"]},
      {"title":"Fix the metric","instructions":"Order the lines.","snippets":["tp = 0","for y, p in pairs:","  if y == p == 1:","    tp += 1","precision = tp / max(1, predicted)","print(precision)"]}
     ]}""";

  @Autowired
  private ManualClock clock;

  @Autowired
  private ObjectMapper json;

  @MockitoSpyBean
  private JdbcTemplate spiedJdbc;

  private long ai;
  private TestTeam alpha;
  private TestTeam alfa;
  private TestTeam bravo;

  @BeforeEach
  void prepare() {
    clock.reset();
    loadContent();
    ai = rooms.get(RoomCode.AI);
    alpha = team("Alpha", GroupCode.A);
    alfa = team("Alfa", GroupCode.A);
    bravo = team("Bravo", GroupCode.B);
    expectOk(put("/admin/settings", admin, "{\"roundMinutes\":5}"));
    expectOk(setActivity(ai, "\"AI_QUIZ\"", admin));
    expectOk(post("/rooms/" + ai + "/quiz/import", managers.get(RoomCode.AI), QUIZ));
  }

  // ---- what teams see -----------------------------------------------------------------------------

  @Test
  void memberResponsesNeverContainCorrectAnswersScoresOrThePassScore() {
    setPassScore(37);
    startRound1();
    Response view = expectOk(activity(alpha));
    assertEquals("MCQ", view.json("$.stage"));
    assertEquals(6, (int) view.json("$.questions.length()"));
    assertEquals(List.of(), view.json("$.repairs"), "repairs are not shown during the MCQ part");
    assertNull(view.json("$.result"));
    assertNoSecrets(view.body());

    Response saved = expectOk(answer(alpha, UUID.randomUUID(), questionId(0), correctOption(questionId(0))));
    assertEquals("Response saved", saved.json("$.message"));
    assertNoSecrets(saved.body());
    finishMcq(alpha);

    Response repairs = expectOk(activity(alpha));
    assertEquals("REPAIRS", repairs.json("$.stage"));
    assertEquals(List.of(), repairs.json("$.questions"), "questions are hidden once the MCQ part is over");
    for (int r = 0; r < 3; r++) {
      List<Integer> shown = repairs.json("$.repairs[" + r + "].snippets[*].id");
      assertNotEquals(correctOrder(repairId(r)), longs(shown), "never the correct order");
    }
    assertNoSecrets(repairs.body());
    for (int r = 0; r < 3; r++) {
      Response receipt = expectOk(submitRepair(alpha, UUID.randomUUID(), repairId(r), correctOrder(repairId(r))));
      assertNoSecrets(receipt.body());
    }
    // 2 + 30 = 32 points < 37: evaluated, not passed.
    Response done = expectOk(activity(alpha));
    assertEquals(false, done.json("$.result.clueUnlocked"));
    assertNull(done.json("$.result.clue"));
    assertEquals(List.of(), done.json("$.repairs"), "after the evaluation only the outcome is shown");
    assertNoSecrets(done.body());
    assertFalse(done.body().contains(AI_KEY), "no clue without a pass");

    String board = expectOk(get("/team/board", alpha.token())).body();
    String receipts = expectOk(get("/team/submissions", alpha.token())).body();
    for (String body : List.of(board, receipts)) {
      for (String secret : List.of(EXPLANATION, "pointsEarned", "passScore", "total", "32.00", "AI quiz result", "MCQ 1/6", "correctOption")) {
        assertFalse(body.contains(secret), secret + " in " + body);
      }
    }
    assertEquals("[]", receipts, "the evaluation is not one of the team's receipts");
    String evaluation = db.queryForObject("SELECT submission_id::text FROM quiz_evaluation WHERE team_id = ?", String.class, alpha.id());
    expectError(get("/team/submissions/" + evaluation, alpha.token()), 404, "Submission not found.");
    assertEquals(List.of(true), JsonPathList.read(board, "$.evidence[?(@.roomCode == 'AI')].finished"), "the room is finished at once");
    assertEquals(List.of(), JsonPathList.read(board, "$.evidence[?(@.roomCode == 'AI' && @.clueCorrect != null)]"));
    assertNull(JsonPathList.read(board, "$.rooms[0].lastReceipt"), "no receipt for the AI room");

    expectOk(post("/admin/round/timer/end", admin, null));
    Response timeUp = expectOk(activity(alpha));
    assertEquals("TIME_UP", timeUp.json("$.stage"));
    assertNoSecrets(timeUp.body());
  }

  @Test
  void questionsAreShuffledWithinSectionsAndOptionsPerTeamButStableOnReload() {
    startRound1();
    Response first = expectOk(activity(alpha));
    assertEquals(first.body().replaceAll("\"serverNow\":\"[^\"]+\"", ""), expectOk(activity(alpha)).body().replaceAll("\"serverNow\":\"[^\"]+\"", ""));
    List<String> sections = first.json("$.questions[*].section");
    assertEquals(List.of("EASY", "EASY", "MEDIUM", "ADVANCED", "LANDMARK", "RECENT"), sections, "section order is kept");
    List<Integer> numbers = first.json("$.questions[*].number");
    assertEquals(List.of(1, 2, 3, 4, 5, 6), numbers);

    // Option orders differ between teams for at least one question (16 options in all).
    Set<String> orders = new HashSet<>();
    for (TestTeam team : List.of(alpha, alfa, team("Ace", GroupCode.A))) {
      orders.add(expectOk(activity(team)).json("$.questions[*].options[*].id").toString());
    }
    assertTrue(orders.size() > 1, "teams get their own option orders: " + orders);
  }

  // ---- answers lock -------------------------------------------------------------------------------

  @Test
  void anAnswerIsSavedOnceRetriesReplayAndChangesAreRefused() {
    startRound1();
    long question = questionId(2);
    long right = correctOption(question);
    long wrong = wrongOption(question);
    UUID key = UUID.randomUUID();
    long revision = revision();

    Response first = expectOk(answer(alpha, key, question, wrong));
    Response retry = expectOk(answer(alpha, key, question, wrong));
    assertEquals(first.body(), retry.body(), "the same key and body give the same receipt");
    expectError(answer(alpha, key, question, right), 409, "This Idempotency-Key was already used for a different request.");
    expectError(answer(alpha, UUID.randomUUID(), question, right), 409, "Already answered.");
    expectError(answer(alpha, UUID.randomUUID(), question, wrong), 409, "Already answered.");
    assertEquals(1, count("SELECT count(*) FROM quiz_mcq_answer WHERE team_id = " + alpha.id()));
    assertEquals(revision, revision(), "answers do not bump the global revision");

    Response view = expectOk(activity(alpha));
    List<Object> selected = view.json("$.questions[?(@.id == " + question + ")].selectedOptionId");
    assertEquals(wrong, ((Number) selected.getFirst()).longValue(), "the team sees its own saved choice");
    assertEquals(1, (int) view.json("$.answeredCount"));

    expectError(answer(alpha, UUID.randomUUID(), question, optionOf(questionId(3))), 400, "Choose one of the question's options.");
    expectError(answer(alpha, UUID.randomUUID(), 999_999, right), 404, "Question not found.");
  }

  @Test
  void aRepairIsSubmittedOnceAndAWrongSetOfSnippetsIsRefused() {
    startRound1();
    finishMcq(alpha);
    long repair = repairId(0);
    List<Long> order = correctOrder(repair);
    UUID key = UUID.randomUUID();
    Response first = expectOk(submitRepair(alpha, key, repair, order));
    assertEquals(first.body(), expectOk(submitRepair(alpha, key, repair, order)).body());
    List<Long> reversed = new ArrayList<>(order);
    Collections.reverse(reversed);
    expectError(submitRepair(alpha, UUID.randomUUID(), repair, reversed), 409, "Already answered.");

    long other = repairId(1);
    expectError(submitRepair(alpha, UUID.randomUUID(), other, order), 400, "Submit every snippet of this repair exactly once.");
    List<Long> twice = new ArrayList<>(correctOrder(other));
    twice.set(1, twice.getFirst());
    expectError(submitRepair(alpha, UUID.randomUUID(), other, twice), 400, "Submit every snippet of this repair exactly once.");
    expectError(submitRepair(alpha, UUID.randomUUID(), 999_999, order), 404, "Repair not found.");
  }

  // ---- timing -------------------------------------------------------------------------------------

  @Test
  void mcqsCloseAtTheCutoffOrAfterFinishMcqsAndRepairsOpenThen() {
    startRound1();
    expectError(submitRepair(alpha, UUID.randomUUID(), repairId(0), correctOrder(repairId(0))), 409, "The repairs open after the multiple-choice part.");
    expectOk(answer(alpha, UUID.randomUUID(), questionId(0), correctOption(questionId(0))));
    assertEquals(120, (int) expectOk(activity(alpha)).json("$.mcqRemainingSeconds"));

    Response finished = expectOk(post("/team/rooms/" + ai + "/activity/finish-mcq", alfa.token(), null));
    assertEquals("REPAIRS", finished.json("$.stage"));
    assertEquals(true, finished.json("$.mcqFinished"));
    expectError(answer(alfa, UUID.randomUUID(), questionId(0), correctOption(questionId(0))), 409, "The multiple-choice part is over.");
    expectOk(post("/team/rooms/" + ai + "/activity/finish-mcq", alfa.token(), null));
    assertEquals(1, count("SELECT count(*) FROM quiz_team_progress WHERE mcq_finished_at IS NOT NULL"), "finishing is irreversible and once");

    clock.advance(Duration.ofSeconds(119));
    expectOk(answer(alpha, UUID.randomUUID(), questionId(1), correctOption(questionId(1))));
    clock.advance(Duration.ofSeconds(1));
    expectError(answer(alpha, UUID.randomUUID(), questionId(2), correctOption(questionId(2))), 409, "The multiple-choice part is over.");
    Response view = expectOk(activity(alpha));
    assertEquals("REPAIRS", view.json("$.stage"));
    assertEquals(0, (int) view.json("$.mcqRemainingSeconds"));
    expectOk(submitRepair(alpha, UUID.randomUUID(), repairId(0), correctOrder(repairId(0))));
  }

  @Test
  void pausesDoNotCountAndNothingIsAcceptedWhilePaused() {
    startRound1();
    clock.advance(Duration.ofSeconds(60));
    expectOk(post("/admin/round/timer/pause", admin, null));
    expectError(answer(alpha, UUID.randomUUID(), questionId(0), correctOption(questionId(0))), 409, "The round is paused.");
    expectError(post("/team/rooms/" + ai + "/activity/finish-mcq", alpha.token(), null), 409, "The round is paused.");
    clock.advance(Duration.ofSeconds(120));
    Response paused = expectOk(activity(alpha));
    assertEquals("MCQ", paused.json("$.stage"));
    assertEquals(60, (int) paused.json("$.mcqRemainingSeconds"), "paused time does not count");
    expectOk(post("/admin/round/timer/resume", admin, null));
    clock.advance(Duration.ofSeconds(59));
    expectOk(answer(alpha, UUID.randomUUID(), questionId(0), correctOption(questionId(0))));
  }

  @Test
  void nothingIsAcceptedBeforeTheTimerOutsideTheTeamsRoundOrAfterTimeUp() {
    assignRound(1);
    openRoom(RoomCode.AI);
    Response waiting = expectOk(activity(alpha));
    assertEquals("NOT_STARTED", waiting.json("$.stage"));
    assertEquals(List.of(), waiting.json("$.questions"), "no question is visible before the timer starts");
    expectError(answer(alpha, UUID.randomUUID(), questionId(0), correctOption(questionId(0))), 409, "The timer has not started yet.");

    startTimer();
    closeRoom(RoomCode.AI);
    expectError(answer(alpha, UUID.randomUUID(), questionId(0), correctOption(questionId(0))), 409, "This room is closed.");
    openRoom(RoomCode.AI);
    expectError(activity(bravo), 404, "Activity not found.");
    expectError(answer(bravo, UUID.randomUUID(), questionId(0), correctOption(questionId(0))), 404, "Activity not found.");
    expectError(
      submit(alpha, RoomCode.AI, UUID.randomUUID(), "output", AI_KEY),
      409,
      "This room records its result from its activity; there is no answer form."
    );

    expectOk(post("/admin/round/timer/end", admin, null));
    expectError(answer(alpha, UUID.randomUUID(), questionId(0), correctOption(questionId(0))), 409, "Time is up for this round.");
    expectError(post("/team/rooms/" + ai + "/activity/finish-mcq", alpha.token(), null), 409, "Time is up for this round.");

    setRound(2);
    expectError(activity(alpha), 404, "Activity not found.");
    expectError(answer(alpha, UUID.randomUUID(), questionId(0), correctOption(questionId(0))), 404, "Activity not found.");
    setRound(4);
    assertEquals("MCQ", expectOk(activity(bravo)).json("$.stage"), "group B is in the AI room in round 4");
    assertEquals(0, count("SELECT count(*) FROM quiz_mcq_answer"));
  }

  // ---- evaluation ---------------------------------------------------------------------------------

  @Test
  void aTotalEqualToThePassScorePassesAndUnlocksTheClueJustBelowDoesNot() {
    setPassScore(47);
    startRound1();
    TestTeam ace = team("Ace", GroupCode.A);
    // Alfa: 22 MCQ points + 5 (repair 1: 2 of 4 in place) + 10 + 10 = 47.00.
    answerAll(alfa, Set.of());
    finishMcq(alfa);
    List<List<Long>> alfaOrders = List.of(swapFirstTwo(correctOrder(repairId(0))), correctOrder(repairId(1)), correctOrder(repairId(2)));
    long before = revision();
    for (int r = 0; r < 2; r++) expectOk(submitRepair(alfa, UUID.randomUUID(), repairId(r), alfaOrders.get(r)));
    assertEquals(0, count("SELECT count(*) FROM quiz_evaluation"), "no evaluation before the last repair");
    assertEquals(before, revision());
    expectOk(submitRepair(alfa, UUID.randomUUID(), repairId(2), alfaOrders.get(2)));
    assertTrue(revision() > before, "the evaluation is announced to every screen");
    // Ace: 20 MCQ points (question 1 wrong) + 10 + 10 + 6.67 (repair 3: 4 of 6 in place) = 46.67.
    answerAll(ace, Set.of(0));
    finishMcq(ace);
    for (int r = 0; r < 2; r++) expectOk(submitRepair(ace, UUID.randomUUID(), repairId(r), correctOrder(repairId(r))));
    expectOk(submitRepair(ace, UUID.randomUUID(), repairId(2), swapFirstTwo(correctOrder(repairId(2)))));

    assertEquals(new BigDecimal("47.00"), evaluationTotal(alfa));
    assertEquals(new BigDecimal("46.67"), evaluationTotal(ace));
    assertEquals(List.of("LAST_REPAIR"), db.queryForList("SELECT DISTINCT cause FROM quiz_evaluation", String.class));
    assertEquals(47, count("SELECT pass_score FROM quiz_evaluation WHERE team_id = " + alfa.id()));

    Response passed = expectOk(activity(alfa));
    assertEquals(true, passed.json("$.result.clueUnlocked"));
    assertEquals(AI_KEY, passed.json("$.result.clue"));
    Response failed = expectOk(activity(ace));
    assertEquals(false, failed.json("$.result.clueUnlocked"));
    assertNull(failed.json("$.result.clue"));

    // Recorded through the room-submission path: a CORRECT submission with its verdict and a ROOM clue.
    assertEquals(true, db.queryForObject("SELECT clue_correct FROM room_submission WHERE team_id = ?", Boolean.class, alfa.id()));
    assertEquals(false, db.queryForObject("SELECT clue_correct FROM room_submission WHERE team_id = ?", Boolean.class, ace.id()));
    assertEquals("ROOM", db.queryForObject("SELECT source FROM collected_clue WHERE team_id = ? AND room_id = ?", String.class, alfa.id(), ai));
    assertEquals(0, count("SELECT count(*) FROM collected_clue WHERE team_id = " + ace.id()));
    assertEquals(
      "AI quiz · MCQ 6/6 answered · repairs 3/3 submitted",
      db.queryForObject("SELECT technical_output FROM room_submission WHERE team_id = ?", String.class, alfa.id())
    );

    Response board = expectOk(get("/team/board", alfa.token()));
    assertEquals(List.of(AI_KEY), board.json("$.evidence[?(@.roomCode == 'AI')].clueValue"), "the clue card shows the clue while the round runs");
    assertEquals(List.of(true), board.json("$.evidence[?(@.roomCode == 'AI')].resultRecorded"));
    assertEquals(1, (int) board.json("$.cluesCollected"));
    assertEquals(List.of(), expectOk(get("/team/board", ace.token())).json("$.evidence[?(@.roomCode == 'AI' && @.clueValue != null)]"));
    expectError(answer(alfa, UUID.randomUUID(), questionId(0), correctOption(questionId(0))), 409, EVALUATED);
  }

  @Test
  void theLastRepairAndTheRoundEndRacingEvaluateTheTeamOnce() throws Exception {
    setPassScore(30);
    startRound1();
    finishMcq(alpha);
    for (int r = 0; r < 2; r++) expectOk(submitRepair(alpha, UUID.randomUUID(), repairId(r), correctOrder(repairId(r))));
    long last = repairId(2);
    List<Long> order = correctOrder(last);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Response> repair = pool.submit(() -> {
        go.await();
        return submitRepair(alpha, UUID.randomUUID(), last, order);
      });
      Future<Response> end = pool.submit(() -> {
        go.await();
        return post("/admin/round/timer/end", admin, null);
      });
      go.countDown();
      Response repaired = repair.get(30, TimeUnit.SECONDS);
      expectOk(end.get(30, TimeUnit.SECONDS));

      String cause = db.queryForObject("SELECT cause FROM quiz_evaluation WHERE team_id = ?", String.class, alpha.id());
      if (repaired.status() == 200) {
        assertEquals("LAST_REPAIR", cause, "the repair won: 30 points, passed");
        assertEquals(1, count("SELECT count(*) FROM collected_clue WHERE team_id = " + alpha.id()));
      } else {
        expectError(repaired, 409, "Time is up for this round.");
        assertEquals("TIME_UP", cause, "the round end won: 20 points, not passed");
        assertEquals(0, count("SELECT count(*) FROM collected_clue WHERE team_id = " + alpha.id()));
      }
    } finally {
      pool.shutdownNow();
    }
    expectOk(post("/admin/round/timer/end", admin, null));
    setRound(2);
    assertEquals(1, count("SELECT count(*) FROM quiz_evaluation WHERE team_id = " + alpha.id()));
    assertEquals(1, count("SELECT count(*) FROM room_submission WHERE team_id = " + alpha.id()));
    assertEquals(1, count("SELECT count(*) FROM audit_event WHERE action = 'EVENT_QUIZ_EVALUATED' AND target LIKE '% team:" + alpha.id() + " %'"));
  }

  @Test
  void aTeamThatFinishedIsNotEvaluatedAgainAndItsClueIsAddedOnce() {
    setPassScore(30);
    startRound1();
    finishMcq(alpha);
    for (int r = 0; r < 3; r++) expectOk(submitRepair(alpha, UUID.randomUUID(), repairId(r), correctOrder(repairId(r))));
    expectError(submitRepair(alpha, UUID.randomUUID(), repairId(0), correctOrder(repairId(0))), 409, EVALUATED);
    expectError(post("/team/rooms/" + ai + "/activity/finish-mcq", alpha.token(), null), 409, EVALUATED);
    expectOk(post("/admin/round/timer/end", admin, null));
    setRound(2);
    assertEquals(1, count("SELECT count(*) FROM quiz_evaluation WHERE team_id = " + alpha.id()));
    assertEquals(1, count("SELECT count(*) FROM collected_clue WHERE team_id = " + alpha.id()));
    assertEquals("LAST_REPAIR", db.queryForObject("SELECT cause FROM quiz_evaluation WHERE team_id = ?", String.class, alpha.id()));
  }

  @Test
  void theRoundEndEvaluatesEveryUnfinishedTeamOfTheRoomOnce() {
    startRound1();
    TestTeam out = team("Delta", GroupCode.A);
    expectOk(post("/admin/teams/" + out.id() + "/disqualify", admin, "{\"reason\":\"Left the event early\"}"));
    expectOk(answer(alpha, UUID.randomUUID(), questionId(0), correctOption(questionId(0))));
    finishMcq(alpha);
    expectOk(submitRepair(alpha, UUID.randomUUID(), repairId(0), correctOrder(repairId(0))));
    assertEquals(0, count("SELECT count(*) FROM quiz_evaluation"));

    long before = revision();
    expectOk(post("/admin/round/timer/end", admin, null));
    assertTrue(revision() > before);
    assertEquals(
      List.of("Alfa TIME_UP false 0.00", "Alpha TIME_UP false 12.00"),
      db.queryForList(
        "SELECT t.name || ' ' || e.cause || ' ' || e.passed || ' ' || e.total FROM quiz_evaluation e JOIN event_team t ON t.id = e.team_id ORDER BY t.name",
        String.class
      ),
      "Bravo is in another room and Delta is disqualified"
    );
    assertEquals("AI quiz result", db.queryForObject("SELECT clue_answer FROM room_submission WHERE team_id = ?", String.class, alpha.id()));
    assertEquals(
      "AI quiz · MCQ 1/6 answered · repairs 1/3 submitted",
      db.queryForObject("SELECT technical_output FROM room_submission WHERE team_id = ?", String.class, alpha.id())
    );
    Response failed = expectOk(activity(alpha));
    assertEquals(false, failed.json("$.result.clueUnlocked"));

    long after = revision();
    expectOk(post("/admin/round/timer/end", admin, null));
    assertEquals(after, revision(), "ending again changes nothing");
    assertEquals(2, count("SELECT count(*) FROM quiz_evaluation"));
  }

  @Test
  void theNextRoundEvaluatesATimerThatRanOutByItselfButNotAnUnstartedRound() {
    assignRound(1);
    setRound(2);
    assertEquals(0, count("SELECT count(*) FROM quiz_evaluation"), "a round whose timer never started is not evaluated");

    assignRound(4);
    startTimer();
    openRoom(RoomCode.AI);
    expectOk(answer(bravo, UUID.randomUUID(), questionId(0), correctOption(questionId(0))));
    clock.advance(Duration.ofMinutes(5).plusSeconds(1));
    Response timeUp = expectOk(activity(bravo));
    assertEquals("TIME_UP", timeUp.json("$.stage"));
    assertNull(timeUp.json("$.result"), "no stored transition yet: the result waits for the round's end");
    expectOk(post("/admin/round", admin, "{\"round\":5}"));
    assertEquals("TIME_UP", db.queryForObject("SELECT cause FROM quiz_evaluation WHERE team_id = ?", String.class, bravo.id()));
  }

  @Test
  void aManualVerdictOverrideStillDecidesTheClueAndKeyChangesKeepTheResult() {
    setPassScore(30);
    startRound1();
    finishMcq(alpha);
    for (int r = 0; r < 3; r++) expectOk(submitRepair(alpha, UUID.randomUUID(), repairId(r), correctOrder(repairId(r))));
    expectOk(post("/admin/round/timer/end", admin, null));
    String manager = managers.get(RoomCode.AI);

    expectOk(override(evaluationSubmission(alpha), "REJECT", manager));
    assertEquals(0, count("SELECT count(*) FROM collected_clue WHERE team_id = " + alpha.id()));
    assertEquals(false, expectOk(activity(alpha)).json("$.result.clueUnlocked"));
    expectOk(override(evaluationSubmission(alfa), "ACCEPT", admin));
    Response granted = expectOk(activity(alfa));
    assertEquals(true, granted.json("$.result.clueUnlocked"));
    assertEquals(AI_KEY, granted.json("$.result.clue"));

    expectOk(setClueKey(RoomCode.AI, "BLACK VAN"));
    assertEquals("BLACK VAN", expectOk(activity(alfa)).json("$.result.clue"), "the clue follows the current key");
    assertEquals(1, count("SELECT count(*) FROM collected_clue WHERE team_id = " + alfa.id()), "a key change never re-checks a quiz result");

    Response results = expectOk(get("/rooms/" + ai + "/quiz/results", manager));
    assertEquals(List.of("PASSED"), results.json("$.teams[?(@.teamName == 'Alpha')].status"));
    assertEquals(List.of(false), results.json("$.teams[?(@.teamName == 'Alpha')].clueGranted"));
    assertEquals(List.of("NOT_PASSED"), results.json("$.teams[?(@.teamName == 'Alfa')].status"));
    assertEquals(List.of(true), results.json("$.teams[?(@.teamName == 'Alfa')].clueGranted"));
  }

  @Test
  void thePassScoreLocksWithTheFirstEvaluation() {
    String manager = managers.get(RoomCode.AI);
    Response editor = setPassScore(55);
    assertEquals(55, (int) editor.json("$.passScore"));
    assertEquals(false, editor.json("$.passScoreLocked"));
    assertEquals(400, put("/rooms/" + ai + "/quiz/settings", manager, "{\"mcqMinutes\":2,\"passScore\":101}").status());
    assertEquals(55, (int) expectOk(get("/rooms/" + ai + "/quiz/export", manager)).json("$.passScore"));

    startRound1();
    expectOk(post("/admin/round/timer/end", admin, null));
    String locked = "A team in this room has already been evaluated; the pass score can no longer change.";
    expectError(put("/rooms/" + ai + "/quiz/settings", manager, "{\"mcqMinutes\":2,\"passScore\":50}"), 409, locked);
    Response same = expectOk(put("/rooms/" + ai + "/quiz/settings", manager, "{\"mcqMinutes\":3,\"passScore\":55}"));
    assertEquals(3, (int) same.json("$.mcqMinutes"), "the MCQ cutoff stays editable");
    assertEquals(true, same.json("$.passScoreLocked"));
    expectError(post("/rooms/" + ai + "/quiz/import", manager, QUIZ.replace("\"mcqMinutes\":2,", "\"mcqMinutes\":2,\"passScore\":40,")), 409, locked);
    assertEquals(List.of(55), db.queryForList("SELECT DISTINCT pass_score FROM quiz_evaluation", Integer.class));
  }

  @Test
  void anAiQuizRoomWithoutQuestionsOrClueKeyBlocksTheTimerUnlessStartedAnyway() {
    assertEquals(List.of(), expectOk(get("/admin/board", admin)).json("$.startBlockers"));
    long linux = rooms.get(RoomCode.LINUX);
    expectOk(setActivity(linux, "\"AI_QUIZ\"", admin));
    db.update("UPDATE event_room SET clue_key = '' WHERE id = ?", ai);

    Response board = expectOk(get("/admin/board", admin));
    List<String> blocking = board.json("$.alerts[?(@.blocking == true)].message");
    assertEquals(
      List.of(
        "Room AI runs the AI quiz but has no clue key, so passing teams would get no clue.",
        "Room Linux runs the AI quiz but has no questions."
      ),
      blocking
    );
    assertEquals(blocking, board.json("$.startBlockers"));
    assertEquals(List.of("/event/room/" + linux + "/quiz"), board.json("$.alerts[?(@.code == 'QUIZ_WITHOUT_QUESTIONS')].link"));

    assignRound(1);
    expectError(
      post("/admin/round/timer/start", admin, null),
      409,
      "The AI quiz is not ready: " + String.join(" ", blocking) + " Press Start anyway to start regardless."
    );
    assertEquals("ASSIGNED", expectOk(get("/admin/board", admin)).json("$.state.phase"));
    expectOk(post("/admin/round/timer/start", admin, "{\"anyway\":true}"));
    assertEquals("RUNNING", expectOk(get("/admin/board", admin)).json("$.state.phase"));
    assertEquals(1, count("SELECT count(*) FROM audit_event WHERE action = 'EVENT_TIMER_STARTED' AND target = 'round:1 anyway'"));
  }

  // ---- access -------------------------------------------------------------------------------------

  @Test
  void otherTeamsRoomsAndManagersSeeNothing() {
    startRound1();
    long linux = rooms.get(RoomCode.LINUX);
    expectError(get("/team/rooms/" + linux + "/activity", alpha.token()), 404, "Activity not found.");
    expectError(get("/team/rooms/999999/activity", alpha.token()), 404, "Activity not found.");
    expectError(post("/team/rooms/" + linux + "/activity/finish-mcq", alpha.token(), null), 404, "Activity not found.");
    expectError(submitRepair(bravo, UUID.randomUUID(), repairId(0), correctOrder(repairId(0))), 404, "Activity not found.");
    assertEquals(401, get("/team/rooms/" + ai + "/activity", null).status());
    expectError(activity(alpha, managers.get(RoomCode.AI)), 403, "A team account is required.");

    for (String path : List.of("/quiz", "/quiz/results", "/quiz/export", "/quiz/results.csv")) {
      expectError(get("/rooms/" + ai + path, managers.get(RoomCode.LINUX)), 404, "Room not found.");
      expectError(get("/rooms/" + ai + path, alpha.token()), 403, "Access denied.");
      expectOk(get("/rooms/" + ai + path, managers.get(RoomCode.AI)));
      expectOk(get("/rooms/" + ai + path, admin));
    }
    expectError(post("/rooms/" + ai + "/quiz/import", managers.get(RoomCode.LINUX), QUIZ), 404, "Room not found.");
    expectError(put("/rooms/" + ai + "/quiz/settings", managers.get(RoomCode.LINUX), "{\"mcqMinutes\":2,\"passScore\":10}"), 404, "Room not found.");
    expectError(setActivity(ai, "null", managers.get(RoomCode.AI)), 403, "Access denied.");
  }

  // ---- content ------------------------------------------------------------------------------------

  @Test
  void importAndExportRoundTripAndBadFilesAreRefusedWithTheItemNamed() throws Exception {
    String manager = managers.get(RoomCode.AI);
    Response exported = expectOk(get("/rooms/" + ai + "/quiz/export", manager));
    JsonNode expected = json.readTree(QUIZ);
    JsonNode actual = json.readTree(exported.body());
    assertEquals(2, actual.get("mcqMinutes").asInt());
    assertEquals(60, actual.get("passScore").asInt());
    assertEquals(expected.get("questions").size(), actual.get("questions").size());
    for (int i = 0; i < expected.get("questions").size(); i++) {
      JsonNode q = expected.get("questions").get(i);
      JsonNode e = actual.get("questions").get(i);
      for (String field : List.of("section", "prompt", "options", "correctOption", "points")) assertEquals(q.get(field), e.get(field), field);
    }
    for (int i = 0; i < 3; i++) {
      assertEquals(expected.get("repairs").get(i).get("snippets"), actual.get("repairs").get(i).get("snippets"));
    }
    assertEquals(10, actual.get("repairs").get(2).get("points").asInt(), "repair points default to 10");

    expectOk(post("/rooms/" + ai + "/quiz/import", manager, exported.body().replace("\"passScore\":60", "\"passScore\":45")));
    assertEquals(45, (int) expectOk(get("/rooms/" + ai + "/quiz", manager)).json("$.passScore"), "an import may set the pass score");
    expectOk(post("/rooms/" + ai + "/quiz/import", manager, exported.body()));
    assertEquals(exported.body(), expectOk(get("/rooms/" + ai + "/quiz/export", manager)).body(), "export → import → export is stable");

    Response editor = expectOk(get("/rooms/" + ai + "/quiz", manager));
    assertEquals(52, (int) editor.json("$.totalPoints"));
    assertEquals(EXPLANATION + " 1", editor.json("$.questions[0].explanation"), "organizers see explanations");
    assertTrue(((List<?>) editor.json("$.warnings")).contains("The total is 52 points (planned 100)."));

    expectError(
      post("/rooms/" + ai + "/quiz/import", manager, "{\"questions\":[{\"section\":\"EASY\",\"prompt\":\"Q\",\"options\":[\"a\"],\"correctOption\":0,\"points\":1}]}"),
      400,
      "Question 1: A question needs 2 to 6 options."
    );
    expectError(
      post("/rooms/" + ai + "/quiz/import", manager, "{\"questions\":[{\"section\":\"HARD\",\"prompt\":\"Q\",\"options\":[\"a\",\"b\"],\"correctOption\":0,\"points\":1}]}"),
      400,
      "Question 1: Unknown section HARD (use EASY, MEDIUM, ADVANCED, LANDMARK or RECENT)."
    );
    expectError(
      post("/rooms/" + ai + "/quiz/import", manager, "{\"repairs\":[{\"title\":\"R\",\"snippets\":[\"a\",\"b\",\"c\"]}]}"),
      400,
      "Repair 1: A repair needs 4 to 6 snippets."
    );
    expectError(post("/rooms/" + ai + "/quiz/import", manager, "{\"passScore\":101}"), 400, "passScore must be between 0 and 100.");
    assertEquals(6, (int) expectOk(get("/rooms/" + ai + "/quiz", manager)).json("$.questions.length()"), "a refused import changes nothing");
  }

  @Test
  void anAnsweredQuestionOrRepairIsLockedAndAnImportNoLongerReplacesContent() {
    String manager = managers.get(RoomCode.AI);
    String edit = "{\"section\":\"EASY\",\"prompt\":\"Changed\",\"options\":[\"x\",\"y\"],\"correctOption\":0,\"points\":1}";
    startRound1();
    expectOk(answer(alpha, UUID.randomUUID(), questionId(0), correctOption(questionId(0))));

    String locked = "A team has already answered this question; it can no longer be changed.";
    expectError(put("/rooms/" + ai + "/quiz/questions/" + questionId(0), manager, edit), 409, locked);
    expectError(delete("/rooms/" + ai + "/quiz/questions/" + questionId(0), manager), 409, locked);
    long unanswered = questionId(1);
    expectOk(put("/rooms/" + ai + "/quiz/questions/" + unanswered, manager, edit));
    expectError(post("/rooms/" + ai + "/quiz/import", manager, QUIZ), 409, "Teams have already answered in this room; its content can no longer be replaced.");
    assertEquals(true, expectOk(get("/rooms/" + ai + "/quiz", manager)).json("$.questions[0].locked"));

    finishMcq(alpha);
    expectOk(submitRepair(alpha, UUID.randomUUID(), repairId(0), correctOrder(repairId(0))));
    String repairEdit = "{\"title\":\"T\",\"snippets\":[\"1\",\"2\",\"3\",\"4\"]}";
    expectError(put("/rooms/" + ai + "/quiz/repairs/" + repairId(0), manager, repairEdit), 409, "A team has already submitted this repair; it can no longer be changed.");
    expectOk(put("/rooms/" + ai + "/quiz/repairs/" + repairId(1), manager, repairEdit));

    openFinal();
    expectError(post("/rooms/" + ai + "/quiz/questions", manager, edit), 409, "Event content is read-only after the final opens.");
  }

  @Test
  void questionsAndRepairsAreValidatedAndEditsNeedTheActivity() {
    String manager = managers.get(RoomCode.AI);
    expectError(
      post("/rooms/" + ai + "/quiz/questions", manager, "{\"section\":\"EASY\",\"prompt\":\"Q\",\"options\":[\"a\",\"a\"],\"correctOption\":0,\"points\":1}"),
      400,
      "Two options have the same text."
    );
    expectError(
      post("/rooms/" + ai + "/quiz/questions", manager, "{\"section\":\"EASY\",\"prompt\":\"Q\",\"options\":[\"a\",\"b\"],\"correctOption\":2,\"points\":1}"),
      400,
      "Mark exactly one option as correct."
    );
    expectError(
      post("/rooms/" + ai + "/quiz/questions", manager, "{\"section\":\"EASY\",\"prompt\":\"Q\",\"options\":[\"a\",\"b\"],\"correctOption\":0,\"points\":11}"),
      400,
      "Points must be between 1 and 10."
    );
    Response created = expectOk(
      post("/rooms/" + ai + "/quiz/questions", manager, "{\"section\":\"RECENT\",\"prompt\":\" New \",\"options\":[\" a \",\"b\",\"c\",\"d\",\"e\",\"f\"],\"correctOption\":5,\"points\":10}")
    );
    assertEquals("New", created.json("$.questions[6].prompt"));
    assertEquals(5, (int) created.json("$.questions[6].correctOption"));
    assertEquals(2, (int) created.json("$.sections[4].questions"));

    expectOk(setActivity(ai, "null", admin));
    expectError(post("/rooms/" + ai + "/quiz/questions", manager, "{\"section\":\"EASY\",\"prompt\":\"Q\",\"options\":[\"a\",\"b\"],\"correctOption\":0,\"points\":1}"), 409, "The AI quiz is not enabled for this room.");
    List<String> audits = db.queryForList("SELECT action || ' ' || target FROM audit_event WHERE action LIKE 'EVENT_QUIZ%' OR action = 'EVENT_ROOM_ACTIVITY'", String.class);
    assertFalse(audits.toString().contains("New"), "audits never carry the question's text: " + audits);
  }

  @Test
  void theAdminSwitchesTheActivityAndTheBoardWarnsAboutThePlan() {
    assignRound(1);
    assertEquals("AI_QUIZ", expectOk(get("/team/board", alpha.token())).json("$.scheduledRoom.activity"));
    assertEquals("AI_QUIZ", expectOk(get("/rooms/" + ai + "/board", managers.get(RoomCode.AI))).json("$.room.activity"));
    assertEquals("AI_QUIZ", expectOk(get("/admin/content", admin)).json("$.rooms[0].activity"));
    List<String> plan = expectOk(get("/admin/board", admin)).json("$.alerts[?(@.code == 'QUIZ_PLAN')].message");
    assertTrue(plan.contains("Room AI quiz: Easy has 2 questions (planned 20)."), plan.toString());
    assertTrue(plan.contains("Room AI quiz: The total is 52 points (planned 100)."), plan.toString());
    List<Boolean> planBlocking = expectOk(get("/admin/board", admin)).json("$.alerts[?(@.code == 'QUIZ_PLAN')].blocking");
    assertEquals(List.of(false), planBlocking.stream().distinct().toList(), "planning warnings never block");

    long before = revision();
    expectOk(setActivity(ai, "null", admin));
    assertTrue(revision() > before);
    long unchanged = revision();
    expectOk(setActivity(ai, "null", admin));
    assertEquals(unchanged, revision(), "repeating the same setting is a no-op");
    assertNull(expectOk(get("/team/board", alpha.token())).json("$.scheduledRoom.activity"));
    expectError(activity(alpha), 404, "Activity not found.");
    assertEquals(List.of(), expectOk(get("/admin/board", admin)).json("$.alerts[?(@.code =~ /QUIZ.*/)]"));
  }

  @Test
  void aTeamResetDeletesItsQuizAnswersAndEvaluationAndTheSnapshotKeepsThem() {
    startRound1();
    expectOk(answer(alpha, UUID.randomUUID(), questionId(0), correctOption(questionId(0))));
    expectOk(answer(alfa, UUID.randomUUID(), questionId(0), correctOption(questionId(0))));
    expectOk(post("/admin/round/timer/end", admin, null));
    expectOk(post("/admin/reset/team/" + alpha.id(), admin, "{\"reason\":\"Quiz reset test\"}"));
    assertEquals(0, count("SELECT count(*) FROM quiz_mcq_answer WHERE team_id = " + alpha.id()));
    assertEquals(0, count("SELECT count(*) FROM quiz_team_progress WHERE team_id = " + alpha.id()));
    assertEquals(0, count("SELECT count(*) FROM quiz_evaluation WHERE team_id = " + alpha.id()));
    assertEquals(1, count("SELECT count(*) FROM quiz_mcq_answer WHERE team_id = " + alfa.id()));
    assertEquals(1, count("SELECT count(*) FROM quiz_evaluation WHERE team_id = " + alfa.id()));
    String data = db.queryForObject("SELECT data::text FROM event_snapshot ORDER BY id DESC LIMIT 1", String.class);
    assertTrue(data.contains("\"quiz_mcq_answer\": [{"), data);
    assertTrue(data.contains("\"quiz_evaluation\": [{"), data);
  }

  // ---- results ------------------------------------------------------------------------------------

  @Test
  void resultsMatchQuizScoringAndRankByTotalThenEarlierCompletion() {
    setPassScore(23);
    startRound1();
    // Alpha: questions 0..2 right, 3..5 wrong; repairs: correct, one swap, nothing in place.
    List<BigDecimal> alphaScores = new ArrayList<>();
    for (int q = 0; q < 6; q++) {
      long question = questionId(q);
      boolean right = q < 3;
      expectOk(answer(alpha, UUID.randomUUID(), question, right ? correctOption(question) : wrongOption(question)));
      alphaScores.add(QuizScoring.mcqPoints(points(question), right));
    }
    clock.advance(Duration.ofSeconds(5));
    finishMcq(alpha);
    List<List<Long>> orders = List.of(correctOrder(repairId(0)), swapFirstTwo(correctOrder(repairId(1))), rotate(correctOrder(repairId(2))));
    for (int r = 0; r < 3; r++) {
      List<Long> order = orders.get(r);
      expectOk(submitRepair(alpha, UUID.randomUUID(), repairId(r), order));
      alphaScores.add(QuizScoring.repairPoints(10, QuizScoring.correctPositions(order, correctOrder(repairId(r))), order.size()));
    }
    BigDecimal alphaTotal = QuizScoring.total(alphaScores);
    assertEquals(new BigDecimal("23.00"), alphaTotal, "7 MCQ points + 10 + 6 + 0");

    // Alfa reaches the same total 5 seconds later (3 + 4 MCQ points, 10 + 6 + 0); Ace a higher one (15 + 10 + 10).
    clock.advance(Duration.ofSeconds(5));
    for (int q = 2; q < 4; q++) expectOk(answer(alfa, UUID.randomUUID(), questionId(q), correctOption(questionId(q))));
    finishMcq(alfa);
    for (int r = 0; r < 3; r++) expectOk(submitRepair(alfa, UUID.randomUUID(), repairId(r), orders.get(r)));
    TestTeam ace = team("Ace", GroupCode.A);
    for (int q = 3; q < 6; q++) expectOk(answer(ace, UUID.randomUUID(), questionId(q), correctOption(questionId(q))));
    finishMcq(ace);
    for (int r = 0; r < 2; r++) expectOk(submitRepair(ace, UUID.randomUUID(), repairId(r), correctOrder(repairId(r))));

    Response results = expectOk(get("/rooms/" + ai + "/quiz/results", managers.get(RoomCode.AI)));
    assertEquals(List.of("Ace", "Alpha", "Alfa"), results.json("$.teams[*].teamName"), "35 > 23 = 23, Alpha finished first");
    assertEquals(List.of(1, 2, 3), results.json("$.teams[*].rank"));
    assertEquals(35.0, ((Number) results.json("$.teams[0].total")).doubleValue());
    assertEquals(alphaTotal.doubleValue(), ((Number) results.json("$.teams[1].total")).doubleValue());
    assertEquals(alphaTotal.doubleValue(), ((Number) results.json("$.teams[2].total")).doubleValue());
    assertEquals(4.0, ((Number) results.json("$.teams[1].mcqBySection.EASY")).doubleValue());
    assertEquals(3.0, ((Number) results.json("$.teams[1].mcqBySection.MEDIUM")).doubleValue());
    assertEquals(0.0, ((Number) results.json("$.teams[1].mcqBySection.RECENT")).doubleValue());
    List<Number> alphaRepairs = results.json("$.teams[1].repairScores");
    assertEquals(List.of(10.0, 6.0, 0.0), alphaRepairs.stream().map(Number::doubleValue).toList());
    assertNull(((List<?>) results.json("$.teams[0].repairScores")).get(2), "Ace never submitted repair 3");
    assertEquals(6, (int) results.json("$.teams[1].answered"));
    assertEquals(52, (int) results.json("$.maxPoints"));
    assertEquals(23, (int) results.json("$.passScore"));
    assertEquals(true, results.json("$.passScoreLocked"));
    assertEquals(List.of("IN_PROGRESS", "PASSED", "PASSED"), results.json("$.teams[*].status"), "23 = 23 passes; Ace has not finished");
    assertEquals(List.of(false, true, true), results.json("$.teams[*].clueGranted"));
    // Question 0: only Alpha answered it (right) → 1 of 3 teams; question 2: Alpha and Alfa → 2 of 3.
    assertEquals(33, (int) results.json("$.questions[0].percentCorrect"));
    assertEquals(67, (int) results.json("$.questions[2].percentCorrect"));
    assertEquals(2, (int) results.json("$.questions[2].correct"));

    String csv = expectOk(get("/rooms/" + ai + "/quiz/results.csv", managers.get(RoomCode.AI))).body();
    String[] lines = csv.split("\r\n");
    assertEquals(
      "rank,team,group,easy,medium,advanced,landmark,recent,mcqTotal,repair1,repair2,repair3,repairTotal,total,passScore,status,clueGranted,answered,repairsSubmitted,mcqFinishedAt,lastAnswerAt,evaluatedAt",
      lines[0]
    );
    assertTrue(lines[1].startsWith("1,\"Ace\",A,0.00,0.00,4.00,5.00,6.00,15.00,10.00,10.00,,20.00,35.00,23,IN_PROGRESS,no,3,2,"), lines[1]);
    assertTrue(lines[2].startsWith("2,\"Alpha\",A,4.00,3.00,0.00,0.00,0.00,7.00,10.00,6.00,0.00,16.00,23.00,23,PASSED,yes,6,3,"), lines[2]);
    assertTrue(lines[1].endsWith(","), "Ace has no evaluation time");
    assertEquals(4, lines.length);
  }

  @Test
  void theCsvNeutralizesFormulasInTeamNames() {
    startRound1();
    TestTeam formula = team("=HYPERLINK(1)", GroupCode.A);
    expectOk(answer(formula, UUID.randomUUID(), questionId(0), correctOption(questionId(0))));
    String csv = expectOk(get("/rooms/" + ai + "/quiz/results.csv", admin)).body();
    assertTrue(csv.contains("\"'=HYPERLINK(1)\""), csv);
  }

  // ---- statements ---------------------------------------------------------------------------------

  @Test
  void theTeamActivityUsesTenStatementsWhateverTheNumberOfTeamsAndAnswers() {
    startRound1();
    List<String> few = activityStatements();
    for (TestTeam team : teams(4)) {
      if (team.group() != GroupCode.A) continue;
      for (int q = 0; q < 6; q++) expectOk(answer(team, UUID.randomUUID(), questionId(q), correctOption(questionId(q))));
    }
    for (int q = 0; q < 6; q++) expectOk(answer(alpha, UUID.randomUUID(), questionId(q), correctOption(questionId(q))));
    List<String> many = activityStatements();
    assertEquals(10, few.size(), few.toString());
    assertEquals(few, many);
  }

  // ---- helpers ------------------------------------------------------------------------------------

  private void startRound1() {
    setRound(1);
    openRoom(RoomCode.AI);
  }

  private Response setPassScore(int passScore) {
    return expectOk(put("/rooms/" + ai + "/quiz/settings", managers.get(RoomCode.AI), "{\"mcqMinutes\":2,\"passScore\":" + passScore + "}"));
  }

  private Response setActivity(long roomId, String activity, String token) {
    return put("/admin/rooms/" + roomId + "/activity", token, "{\"activity\":" + activity + "}");
  }

  private Response activity(TestTeam team) {
    return activity(team, team.token());
  }

  private Response activity(TestTeam team, String token) {
    return get("/team/rooms/" + ai + "/activity", token);
  }

  private Response answer(TestTeam team, UUID key, long questionId, long optionId) {
    return teamPost(team, "/team/rooms/" + ai + "/activity/answers", key, "{\"questionId\":%d,\"optionId\":%d}".formatted(questionId, optionId));
  }

  /** Answers every question, correctly except the listed ones (authoring order). */
  private void answerAll(TestTeam team, Set<Integer> wrong) {
    for (int q = 0; q < 6; q++) {
      long question = questionId(q);
      expectOk(answer(team, UUID.randomUUID(), question, wrong.contains(q) ? wrongOption(question) : correctOption(question)));
    }
  }

  private Response submitRepair(TestTeam team, UUID key, long repairId, List<Long> snippetIds) {
    return teamPost(team, "/team/rooms/" + ai + "/activity/repairs/" + repairId, key, "{\"snippetIds\":" + snippetIds + "}");
  }

  private void finishMcq(TestTeam team) {
    expectOk(post("/team/rooms/" + ai + "/activity/finish-mcq", team.token(), null));
  }

  private Response override(String submissionId, String verdict, String token) {
    String path = "/rooms/" + ai + "/submissions/" + submissionId + "/clue-override";
    return put(path, token, "{\"override\":\"" + verdict + "\",\"version\":1}");
  }

  private String evaluationSubmission(TestTeam team) {
    return db.queryForObject("SELECT submission_id::text FROM quiz_evaluation WHERE team_id = ?", String.class, team.id());
  }

  private BigDecimal evaluationTotal(TestTeam team) {
    return db.queryForObject("SELECT total FROM quiz_evaluation WHERE team_id = ?", BigDecimal.class, team.id());
  }

  /** The n-th question (authoring order). */
  private long questionId(int n) {
    return db.queryForObject("SELECT id FROM quiz_question WHERE room_id = ? ORDER BY position, id OFFSET ? LIMIT 1", Long.class, ai, n);
  }

  private long repairId(int n) {
    return db.queryForObject("SELECT id FROM quiz_repair WHERE room_id = ? ORDER BY position, id OFFSET ? LIMIT 1", Long.class, ai, n);
  }

  private int points(long questionId) {
    return db.queryForObject("SELECT points FROM quiz_question WHERE id = ?", Integer.class, questionId);
  }

  private long correctOption(long questionId) {
    return db.queryForObject("SELECT id FROM quiz_option WHERE question_id = ? AND correct", Long.class, questionId);
  }

  private long wrongOption(long questionId) {
    return db.queryForObject("SELECT id FROM quiz_option WHERE question_id = ? AND NOT correct ORDER BY position LIMIT 1", Long.class, questionId);
  }

  private long optionOf(long questionId) {
    return db.queryForObject("SELECT id FROM quiz_option WHERE question_id = ? ORDER BY position LIMIT 1", Long.class, questionId);
  }

  private List<Long> correctOrder(long repairId) {
    return db.queryForList("SELECT id FROM quiz_snippet WHERE repair_id = ? ORDER BY correct_position", Long.class, repairId);
  }

  private int count(String sql) {
    return db.queryForObject(sql, Integer.class);
  }

  private static List<Long> longs(List<Integer> ids) {
    return ids.stream().map(Integer::longValue).toList();
  }

  private static List<Long> swapFirstTwo(List<Long> order) {
    List<Long> swapped = new ArrayList<>(order);
    Collections.swap(swapped, 0, 1);
    return swapped;
  }

  private static List<Long> rotate(List<Long> order) {
    List<Long> rotated = new ArrayList<>(order);
    Collections.rotate(rotated, 1);
    return rotated;
  }

  /** Member JSON never names a correct option, a score, the pass score or an explanation, and never their values. */
  private static void assertNoSecrets(String body) {
    String lower = body.toLowerCase(Locale.ROOT);
    for (String word : List.of("correct", "explanation", "points", "score", "total", "position", "passed")) {
      assertFalse(lower.contains(word), "member JSON contains '" + word + "': " + body);
    }
    assertFalse(body.contains(EXPLANATION), body);
  }

  /** JsonPath on a raw body (the team board is checked as text and as JSON). */
  private static final class JsonPathList {
    static <T> T read(String body, String path) {
      return com.jayway.jsonpath.JsonPath.read(body, path);
    }
  }

  /**
   * The SQL texts of one GET .../activity without the request's session check (auth_session), consecutive repeats
   * collapsed (JdbcTemplate overloads call each other on the spy).
   */
  private List<String> activityStatements() {
    expectOk(activity(alpha));
    clearInvocations(spiedJdbc);
    expectOk(activity(alpha));
    List<String> statements = new ArrayList<>();
    for (Invocation invocation : mockingDetails(spiedJdbc).getInvocations()) {
      Object[] args = invocation.getArguments();
      if (args.length == 0 || !(args[0] instanceof String sql) || sql.contains("auth_session")) continue;
      if (statements.isEmpty() || !statements.getLast().equals(sql)) statements.add(sql);
    }
    return statements;
  }
}
