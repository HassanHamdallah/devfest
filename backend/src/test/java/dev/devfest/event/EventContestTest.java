package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.service.ContestScoreService;
import dev.devfest.event.service.ContestScoreService.RoomScore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The Problem Solving room (rules §20): the contest link and who sees it, the organizer's typed results and the scores
 * computed from them, the clue release (single, whole round, repeated, racing), that nothing a member receives ever
 * carries a count, point, standing, bonus or eligibility, and that resets and snapshots cover the new tables. In the
 * rotation round 1 visits Problem solving with group B, round 2 with A, round 3 with D and round 4 with C.
 */
class EventContestTest extends EventIntegrationTest {

  private static final String CLUE = CLUE_KEYS.get(RoomCode.PROBLEM_SOLVING);
  private static final String LINK = "https://codeforces.com/contest/2200";
  private static final String NOT_FOUND = "Contest results not found.";
  private static final String TEAM_NOT_FOUND = "Team not found.";
  private static final String REASON = "\"reason\":\"Contest test\"";
  /** A member must never receive any of these as a JSON field name. */
  private static final Pattern FORBIDDEN_FIELDS = Pattern.compile(
    "\"(solved|solvedCount|standing|points|problemPoints|bonus|total|eligible|eligibility|score|rank)\"\\s*:"
  );

  @MockitoSpyBean
  private JdbcTemplate spiedJdbc;

  @Autowired
  private ContestScoreService scoreService;

  private long ps;
  private String manager;

  @BeforeEach
  void prepare() {
    loadContent();
    ps = rooms.get(RoomCode.PROBLEM_SOLVING);
    manager = managers.get(RoomCode.PROBLEM_SOLVING);
    expectOk(put("/admin/rooms/" + ps + "/activity", admin, "{\"activity\":\"CONTEST_RESULTS\"}"));
  }

  // ---- who may do what ----------------------------------------------------------------------------

  @Test
  void onlyTheRoomsManagerAndTheAdminReachTheOrganizerPageEveryoneElseGetsA404() {
    TestTeam bravo = team("Bravo", GroupCode.B);
    String ai = managers.get(RoomCode.AI);
    String result = "{\"problems\":[\"A\"],\"standing\":\"OTHER\"}";
    List<Call> calls = List.of(
      new Call(HttpMethod.GET, "/rooms/" + ps + "/contest-results", null),
      new Call(HttpMethod.GET, "/rooms/" + ps + "/contest-results.csv", null),
      new Call(HttpMethod.PUT, "/rooms/" + ps + "/contest-results/link", "{\"url\":\"" + LINK + "\"}"),
      new Call(HttpMethod.DELETE, "/rooms/" + ps + "/contest-results/link", null),
      new Call(HttpMethod.PUT, "/rooms/" + ps + "/contest-results/teams/" + bravo.id(), result),
      new Call(HttpMethod.POST, "/rooms/" + ps + "/contest-results/teams/" + bravo.id() + "/reveal", null),
      new Call(HttpMethod.POST, "/rooms/" + ps + "/contest-results/rounds/1/reveal", null)
    );
    for (Call call : calls) {
      for (String organizer : List.of(admin, manager)) {
        int status = call(call.method(), call.path(), organizer, call.body(), null).status();
        assertTrue(status != 401 && status != 403 && status != 404, call + " as an organizer answered " + status);
      }
      expectError(call(call.method(), call.path(), ai, call.body(), null), 404, NOT_FOUND);
      expectError(call(call.method(), call.path(), managers.get(RoomCode.HARDWARE), call.body(), null), 404, NOT_FOUND);
      expectError(call(call.method(), call.path(), bravo.token(), call.body(), null), 404, NOT_FOUND);
      assertEquals(401, call(call.method(), call.path(), null, call.body(), null).status(), call.toString());
    }
    // An unknown room and a room without the activity look the same as a forbidden one.
    expectError(get("/rooms/" + Long.MAX_VALUE + "/contest-results", admin), 404, NOT_FOUND);
    expectError(get("/rooms/" + rooms.get(RoomCode.AI) + "/contest-results", admin), 404, NOT_FOUND);
    expectError(get("/rooms/" + rooms.get(RoomCode.AI) + "/contest-results", ai), 404, NOT_FOUND);
    expectOk(put("/admin/rooms/" + ps + "/activity", admin, "{\"activity\":null}"));
    expectError(get("/rooms/" + ps + "/contest-results", manager), 404, NOT_FOUND);
  }

  private record Call(HttpMethod method, String path, String body) {}

  @Test
  void theTeamViewIsForMembersOnly() {
    assertEquals(403, get("/team/rooms/" + ps + "/contest", admin).status());
    assertEquals(403, get("/team/rooms/" + ps + "/contest", manager).status());
    assertEquals(401, get("/team/rooms/" + ps + "/contest", null).status());
  }

  @Test
  void aPlainSubmissionToAContestRoomIsRefusedAndTheActivityIsOnTheTeamsBoard() {
    TestTeam bravo = team("Bravo", GroupCode.B);
    setRound(1);
    openRoom(RoomCode.PROBLEM_SOLVING);
    expectError(
      submit(bravo, RoomCode.PROBLEM_SOLVING, UUID.randomUUID(), "output", CLUE),
      409,
      "This room records its result from its activity; there is no answer form."
    );
    assertEquals("CONTEST_RESULTS", expectOk(get("/team/board", bravo.token())).json("$.scheduledRoom.activity"));
    assertEquals(0, count("SELECT count(*) FROM room_submission"));
  }

  // ---- the contest link ---------------------------------------------------------------------------

  @Test
  void theLinkAcceptsHttpsOnlyIsAuditedWithItsHostAndMovesTheRevision() {
    String path = "/rooms/" + ps + "/contest-results/link";
    for (String bad : List.of("http://codeforces.com/contest/1", "javascript:alert(1)", "codeforces.com/contest/1", "https://a.b/x y", "", "   ", "https://u:p@a.b/x")) {
      assertEquals(400, put(path, manager, "{\"url\":" + quote(bad) + "}").status(), bad);
    }
    assertEquals(400, put(path, manager, "{}").status());
    assertNull(expectOk(get("/rooms/" + ps + "/contest-results", manager)).<Object>json("$.contestUrl"));

    long before = revision();
    Response saved = expectOk(put(path, manager, "{\"url\":\"https://codeforces.com/contest/2200?invite=SECRET\"}"));
    assertEquals("https://codeforces.com/contest/2200?invite=SECRET", saved.json("$.contestUrl"));
    assertEquals(before + 1, revision(), "teams re-read their page");
    assertEquals(
      List.of("EVENT_CONTEST_LINK_SET room:" + ps + " host:codeforces.com"),
      db.queryForList("SELECT action || ' ' || target FROM audit_event WHERE action = 'EVENT_CONTEST_LINK_SET'", String.class)
    );
    assertFalse(db.queryForList("SELECT target FROM audit_event", String.class).toString().contains("SECRET"), "the audit never holds the query string");

    expectOk(put(path, admin, "{\"url\":\"" + LINK + "\"}"));
    assertEquals(LINK, expectOk(get("/rooms/" + ps + "/contest-results", manager)).json("$.contestUrl"));
    long afterSet = revision();
    expectOk(delete(path, manager));
    assertEquals(afterSet + 1, revision());
    assertNull(expectOk(get("/rooms/" + ps + "/contest-results", manager)).<Object>json("$.contestUrl"));
    expectOk(delete(path, manager));
    assertEquals(afterSet + 1, revision(), "clearing nothing changes nothing");
    assertEquals(1, count("SELECT count(*) FROM audit_event WHERE action = 'EVENT_CONTEST_LINK_CLEARED'"));
  }

  @Test
  void theLinkFollowsTheContentLockAfterTheFinalOpens() {
    String path = "/rooms/" + ps + "/contest-results/link";
    expectOk(put(path, manager, "{\"url\":\"" + LINK + "\"}"));
    team("Bravo", GroupCode.B);
    openFinal();
    String locked = "Event content is read-only after the final opens.";
    expectError(put(path, manager, "{\"url\":\"https://codeforces.com/contest/9\"}"), 409, locked);
    expectError(delete(path, admin), 409, locked);
    Response view = expectOk(get("/rooms/" + ps + "/contest-results", manager));
    assertEquals(false, view.json("$.linkEditable"));
    assertEquals(LINK, view.json("$.contestUrl"));
  }

  @Test
  void onlyTheTeamAssignedThisRoundSeesTheLinkAndOnlyWhileTheTimerRunsOrIsPaused() {
    expectOk(put("/rooms/" + ps + "/contest-results/link", manager, "{\"url\":\"" + LINK + "\"}"));
    TestTeam bravo = team("Bravo", GroupCode.B); // round 1
    TestTeam alpha = team("Alpha", GroupCode.A); // round 2
    String contest = "/team/rooms/" + ps + "/contest";

    expectError(get(contest, bravo.token()), 404, "Contest not found.");
    assignRound(1);
    assertNull(expectOk(get(contest, bravo.token())).<Object>json("$.contestUrl"), "assigned, the timer has not started");
    expectError(get(contest, alpha.token()), 404, "Contest not found.");

    startTimer();
    assertEquals(LINK, expectOk(get(contest, bravo.token())).json("$.contestUrl"));
    expectError(get(contest, alpha.token()), 404, "Contest not found.");

    expectOk(post("/admin/round/timer/pause", admin, null));
    assertEquals(LINK, expectOk(get(contest, bravo.token())).json("$.contestUrl"), "paused still shows it");
    expectError(get(contest, alpha.token()), 404, "Contest not found.");
    expectOk(post("/admin/round/timer/resume", admin, null));

    expectOk(post("/admin/round/timer/end", admin, null));
    assertNull(expectOk(get(contest, bravo.token())).<Object>json("$.contestUrl"), "time is up");

    setRound(2);
    expectError(get(contest, bravo.token()), 404, "Contest not found.");
    assertEquals(LINK, expectOk(get(contest, alpha.token())).json("$.contestUrl"));

    setRound(5);
    expectError(get(contest, alpha.token()), 404, "Contest not found.");
    expectError(get(contest, bravo.token()), 404, "Contest not found.");
  }

  @Test
  void aTeamSentToTheRoomByAnExceptionSeesTheLinkAndTheTeamSentAwayDoesNot() {
    expectOk(put("/rooms/" + ps + "/contest-results/link", manager, "{\"url\":\"" + LINK + "\"}"));
    TestTeam bravo = team("Bravo", GroupCode.B); // round 1: Problem solving
    TestTeam charlie = team("Charlie", GroupCode.C); // round 1: Linux
    setRound(1);
    String contest = "/team/rooms/" + ps + "/contest";
    assertEquals(LINK, expectOk(get(contest, bravo.token())).json("$.contestUrl"));
    expectError(get(contest, charlie.token()), 404, "Contest not found.");
    expectOk(
      put(
        "/admin/overrides/room-exception",
        admin,
        "{\"teamId\":%d,\"roomId\":%d,%s}".formatted(charlie.id(), ps, REASON)
      )
    );
    assertEquals(LINK, expectOk(get(contest, charlie.token())).json("$.contestUrl"));
  }

  @Test
  void aDisqualifiedTeamNoLongerGetsTheLink() {
    expectOk(put("/rooms/" + ps + "/contest-results/link", manager, "{\"url\":\"" + LINK + "\"}"));
    TestTeam bravo = team("Bravo", GroupCode.B);
    setRound(1);
    expectOk(post("/admin/teams/" + bravo.id() + "/disqualify", admin, "{" + REASON + "}"));
    assertNull(expectOk(get("/team/rooms/" + ps + "/contest", bravo.token())).<Object>json("$.contestUrl"));
  }

  // ---- results and scores -------------------------------------------------------------------------

  private record Round1(TestTeam winner, TestTeam tiedOne, TestTeam tiedTwo, TestTeam two, TestTeam zero, TestTeam unsaved, TestTeam alpha) {}

  /**
   * Group B (round 1): Winner 6 solved, standing 1st; TiedOne and TiedTwo 3 solved, both 2nd (a tie); Two 2 solved,
   * 3rd; Zero 0 solved although 1st was picked (no bonus); Unsaved has no result. Group A (round 2): Alpha 4 solved,
   * Other.
   */
  private Round1 round1() {
    Round1 s = new Round1(
      team("B-Winner", GroupCode.B),
      team("B-TiedOne", GroupCode.B),
      team("B-TiedTwo", GroupCode.B),
      team("B-Two", GroupCode.B),
      team("B-Zero", GroupCode.B),
      team("B-Unsaved", GroupCode.B),
      team("Alpha", GroupCode.A)
    );
    expectOk(save(s.winner(), "[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\"]", "FIRST"));
    expectOk(save(s.tiedOne(), "[\"A\",\"B\",\"C\"]", "SECOND"));
    expectOk(save(s.tiedTwo(), "[\"B\",\"C\",\"D\"]", "SECOND"));
    expectOk(save(s.two(), "[\"A\",\"B\"]", "THIRD"));
    expectOk(save(s.zero(), "[]", "FIRST"));
    expectOk(save(s.alpha(), "[\"A\",\"C\",\"D\",\"E\"]", "OTHER"));
    return s;
  }

  @Test
  void savingAResultValidatesItsInputAndOnlyAnActiveTeamHasOne() {
    TestTeam bravo = team("Bravo", GroupCode.B);
    TestTeam gone = team("Gone", GroupCode.B);
    TestTeam out = team("Out", GroupCode.B);
    String path = "/rooms/" + ps + "/contest-results/teams/";
    assertEquals(400, put(path + bravo.id(), manager, "{\"problems\":[\"A\"]}").status(), "the standing is required");
    assertEquals(400, put(path + bravo.id(), manager, "{\"problems\":[\"A\"],\"standing\":null}").status());
    assertEquals(400, put(path + bravo.id(), manager, "{\"problems\":[\"A\"],\"standing\":\"FOURTH\"}").status());
    assertEquals(400, put(path + bravo.id(), manager, "{\"problems\":[\"G\"],\"standing\":\"OTHER\"}").status(), "only A to F exist");
    assertEquals(400, put(path + bravo.id(), manager, "{\"standing\":\"FIRST\"}").status());
    expectError(put(path + Long.MAX_VALUE, manager, "{\"problems\":[],\"standing\":\"OTHER\"}"), 404, TEAM_NOT_FOUND);
    db.update("UPDATE event_team SET active = FALSE WHERE id = ?", gone.id());
    expectError(put(path + gone.id(), manager, "{\"problems\":[],\"standing\":\"OTHER\"}"), 404, TEAM_NOT_FOUND);
    expectOk(post("/admin/teams/" + out.id() + "/disqualify", admin, "{" + REASON + "}"));
    expectError(put(path + out.id(), manager, "{\"problems\":[],\"standing\":\"OTHER\"}"), 404, TEAM_NOT_FOUND);
    assertEquals(0, count("SELECT count(*) FROM contest_result"));

    long before = revision();
    Response saved = expectOk(put(path + bravo.id(), manager, "{\"problems\":[\"C\",\"F\",\"A\",\"A\"],\"standing\":\"SECOND\"}"));
    assertEquals(before, revision(), "saving a result does not move the global revision");
    assertEquals(List.of("A", "C", "F"), saved.json("$.rounds[0].teams[0].solved"), "problem F is accepted");
    assertEquals("SECOND", saved.json("$.rounds[0].teams[0].standing"));
    assertEquals(6 + 2, (int) saved.json("$.rounds[0].teams[0].total"));
    assertEquals(0, count("SELECT penalty_minutes FROM contest_result"), "the old penalty column stays unused");
    assertEquals(
      List.of("EVENT_CONTEST_RESULT_SAVED room:" + ps + " team:" + bravo.id() + " solved:ACF standing:SECOND"),
      db.queryForList("SELECT action || ' ' || target FROM audit_event WHERE action = 'EVENT_CONTEST_RESULT_SAVED'", String.class)
    );
    expectOk(put(path + bravo.id(), admin, "{\"problems\":[\"B\"],\"standing\":\"OTHER\"}"));
    assertEquals(1, count("SELECT count(*) FROM contest_result"), "the same row, edited in place");
    assertEquals(List.of("B"), expectOk(get("/rooms/" + ps + "/contest-results", manager)).json("$.rounds[0].teams[?(@.teamName == 'Bravo')].solved[*]"));
  }

  @Test
  void theTableGroupsTeamsByRoundSortsThemByTotalAndComputesEveryNumberOnRead() {
    round1();
    Response view = expectOk(get("/rooms/" + ps + "/contest-results", manager));
    assertEquals(12, (int) view.json("$.maxProblemPoints"));
    assertEquals(15, (int) view.json("$.maxTotal"));
    assertEquals(3, (int) view.json("$.eligibleSolved"));
    assertEquals(List.of("A", "B", "C", "D", "E", "F"), view.json("$.problems[*].problem"));
    assertEquals(List.of(2, 2, 2, 2, 2, 2), view.json("$.problems[*].points"));
    assertEquals(List.of(1, 2), view.json("$.rounds[*].round"));
    assertEquals(List.of("B", "A"), view.json("$.rounds[*].group"));

    assertEquals(List.of("B-Winner", "B-TiedOne", "B-TiedTwo", "B-Two", "B-Unsaved", "B-Zero"), view.json("$.rounds[0].teams[*].teamName"), "total, then solved, then name");
    assertEquals(java.util.Arrays.asList("FIRST", "SECOND", "SECOND", "THIRD", null, "FIRST"), view.json("$.rounds[0].teams[*].standing"));
    assertEquals(List.of(3, 2, 2, 1, 0, 0), view.json("$.rounds[0].teams[*].bonus"), "a tie keeps both bonuses; 1st with nothing solved gets none");
    assertEquals(List.of(12, 6, 6, 4, 0, 0), view.json("$.rounds[0].teams[*].problemPoints"));
    assertEquals(List.of(15, 8, 8, 5, 0, 0), view.json("$.rounds[0].teams[*].total"));
    assertEquals(List.of(6, 3, 3, 2, 0, 0), view.json("$.rounds[0].teams[*].solvedCount"));
    assertEquals(List.of(true, true, true, false, false, false), view.json("$.rounds[0].teams[*].eligible"));
    assertEquals(List.of(true, true, true, true, false, true), view.json("$.rounds[0].teams[*].saved"));
    assertEquals(List.of(6, 5, 3, 3, 0), List.of(
      (int) view.json("$.rounds[0].teamCount"), (int) view.json("$.rounds[0].savedCount"),
      (int) view.json("$.rounds[0].eligibleCount"), (int) view.json("$.rounds[0].releasableCount"),
      (int) view.json("$.rounds[0].releasedCount")
    ));
    assertEquals(List.of("Alpha"), view.json("$.rounds[1].teams[*].teamName"));
    assertEquals(List.of("OTHER"), view.json("$.rounds[1].teams[*].standing"));
    assertEquals(List.of(0), view.json("$.rounds[1].teams[*].bonus"));
    assertEquals(List.of(8), view.json("$.rounds[1].teams[*].total"), "A + C + D + E = 4 x 2, plus 0");
  }

  @Test
  void aTieForFirstKeepsBothBonusesAndNothingComputedIsStored() {
    Round1 s = round1();
    // The unsaved team solves all six and the organizer also picks 1st for it: a tie with the winner, 15 each.
    Response view = expectOk(save(s.unsaved(), "[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\"]", "FIRST"));
    assertEquals(List.of("B-Unsaved", "B-Winner", "B-TiedOne", "B-TiedTwo", "B-Two", "B-Zero"), view.json("$.rounds[0].teams[*].teamName"));
    assertEquals(List.of(3, 3, 2, 2, 1, 0), view.json("$.rounds[0].teams[*].bonus"));
    assertEquals(List.of(15, 15, 8, 8, 5, 0), view.json("$.rounds[0].teams[*].total"));
    // Changing one team's standing changes only that team.
    view = expectOk(save(s.winner(), "[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\"]", "SECOND"));
    assertEquals(List.of(15, 14, 8, 8, 5, 0), view.json("$.rounds[0].teams[*].total"));
    assertEquals(0, count("SELECT count(*) FROM information_schema.columns WHERE table_name IN ('contest_result', 'contest_release', 'contest_room_link') AND (column_name ~ 'total|score|place|bonus|points|eligible')"), "no computed value has a column");
  }

  @Test
  void thereIsAReadOnlyServiceMethodForTheRoomScore() {
    Round1 s = round1();
    assertEquals(Optional.of(new RoomScore(15, 15)), scoreService.roomScore(s.winner().id()));
    assertEquals(Optional.of(new RoomScore(8, 15)), scoreService.roomScore(s.tiedOne().id()));
    assertEquals(Optional.of(new RoomScore(0, 15)), scoreService.roomScore(s.zero().id()), "1st picked but nothing solved");
    assertEquals(Optional.of(new RoomScore(8, 15)), scoreService.roomScore(s.alpha().id()));
    assertEquals(Optional.empty(), scoreService.roomScore(s.unsaved().id()));
    assertEquals(Optional.empty(), scoreService.roomScore(Long.MAX_VALUE));
    expectOk(post("/admin/teams/" + s.two().id() + "/disqualify", admin, "{" + REASON + "}"));
    assertEquals(Optional.empty(), scoreService.roomScore(s.two().id()), "disqualified teams are excluded");
    expectOk(put("/admin/rooms/" + ps + "/activity", admin, "{\"activity\":null}"));
    assertEquals(Optional.empty(), scoreService.roomScore(s.winner().id()), "no room runs the contest");
  }

  @Test
  void savingResultsFeedsOnlyTheProblemSolvingRoomScoreOfTheEventRanking() {
    TestTeam bravo = team("Bravo", GroupCode.B);
    team("Alpha", GroupCode.A);
    Response before = expectOk(get("/admin/scores", admin));
    assertEquals(List.of(0.0, 0.0), ((List<?>) before.json("$[*].roomsTotal")).stream().map(n -> ((Number) n).doubleValue()).toList());
    expectOk(save(bravo, "[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\"]", "FIRST"));
    Response after = expectOk(get("/admin/scores", admin));
    // Six solved is 12 problem points plus the 1st-standing bonus of 3: the room's 15, on the Problem solving line only.
    List<Number> psRoom = after.json("$[?(@.teamName == 'Bravo')].rooms[1].score");
    assertEquals(15.0, psRoom.get(0).doubleValue());
    assertEquals(List.of("CONTEST"), after.json("$[?(@.teamName == 'Bravo')].rooms[1].source"));
    List<Number> bravoTotal = after.json("$[?(@.teamName == 'Bravo')].total");
    assertEquals(15.0, bravoTotal.get(0).doubleValue());
    List<Number> alphaTotal = after.json("$[?(@.teamName == 'Alpha')].total");
    assertEquals(0.0, alphaTotal.get(0).doubleValue());
    List<Number> others = after.json("$[?(@.teamName == 'Bravo')].rooms[0,2,3].score");
    assertEquals(List.of(0.0, 0.0, 0.0), others.stream().map(Number::doubleValue).toList());
    assertEquals(0, count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'event_team' AND column_name ~ 'contest|score|point'"));
  }

  @Test
  void inactiveAndDisqualifiedTeamsAreExcludedFromThePage() {
    Round1 s = round1();
    expectOk(post("/admin/teams/" + s.winner().id() + "/disqualify", admin, "{" + REASON + "}"));
    db.update("UPDATE event_team SET active = FALSE WHERE id = ?", s.zero().id());
    Response view = expectOk(get("/rooms/" + ps + "/contest-results", manager));
    assertEquals(List.of("B-TiedOne", "B-TiedTwo", "B-Two", "B-Unsaved"), view.json("$.rounds[0].teams[*].teamName"));
    assertEquals(java.util.Arrays.asList("SECOND", "SECOND", "THIRD", null), view.json("$.rounds[0].teams[*].standing"), "standings are the organizer's picks: nobody moves up by itself");
    assertEquals(List.of(2, 2, 1, 0), view.json("$.rounds[0].teams[*].bonus"));
    assertEquals(List.of(8, 8, 5, 0), view.json("$.rounds[0].teams[*].total"));
  }

  @Test
  void theCsvListsEveryTeamByRoundAndTotalAndNeutralizesFormulas() {
    Round1 s = round1();
    db.update("UPDATE event_team SET name = '=HYPERLINK(1)' WHERE id = ?", s.unsaved().id());
    Response csv = expectOk(get("/rooms/" + ps + "/contest-results.csv", manager));
    String[] lines = csv.body().split("\r\n");
    assertEquals("round,group,team,saved,solved,solvedCount,problemPoints,standing,bonus,total,eligible,released", lines[0]);
    assertEquals(8, lines.length);
    assertEquals("1,B,\"B-Winner\",true,\"A B C D E F\",6,12,1st,3,15,true,false", lines[1]);
    assertEquals("1,B,\"B-TiedOne\",true,\"A B C\",3,6,2nd,2,8,true,false", lines[2]);
    assertEquals("1,B,\"'=HYPERLINK(1)\",false,\"\",0,0,,0,0,false,false", lines[5]);
    assertEquals("1,B,\"B-Zero\",true,\"\",0,0,1st,0,0,false,false", lines[6]);
    assertTrue(lines[7].startsWith("2,A,\"Alpha\",true,\"A C D E\",4,8,Other,0,8,"), lines[7]);
    assertEquals(1, count("SELECT count(*) FROM audit_event WHERE action = 'EVENT_CONTEST_EXPORT'"));
  }

  @Test
  void aResultSavedBeforeStandingsExistedHasNoStandingAndScoresAsOther() {
    TestTeam bravo = team("Bravo", GroupCode.B);
    // The V18 shape: five problems, a typed penalty, no standing, no problem F.
    db.update(
      "INSERT INTO contest_result(team_id, room_id, solved_a, solved_b, solved_c, solved_d, solved_e, penalty_minutes, saved_by, saved_at) "
        + "VALUES (?, ?, TRUE, TRUE, TRUE, FALSE, FALSE, 40, (SELECT id FROM app_user ORDER BY id LIMIT 1), now())",
      bravo.id(),
      ps
    );
    Response view = expectOk(get("/rooms/" + ps + "/contest-results", manager));
    assertEquals(List.of("A", "B", "C"), view.json("$.rounds[0].teams[0].solved"));
    assertEquals("OTHER", view.json("$.rounds[0].teams[0].standing"));
    assertEquals(0, (int) view.json("$.rounds[0].teams[0].bonus"));
    assertEquals(6, (int) view.json("$.rounds[0].teams[0].total"));
    // Saving again with a standing replaces it.
    view = expectOk(save(bravo, "[\"A\",\"B\",\"C\"]", "FIRST"));
    assertEquals(9, (int) view.json("$.rounds[0].teams[0].total"));
  }

  // ---- the clue release ---------------------------------------------------------------------------

  @Test
  void revealingARefusedTeamExplainsWhy() {
    Round1 s = round1();
    String base = "/rooms/" + ps + "/contest-results/teams/";
    long clues = count("SELECT count(*) FROM collected_clue");
    expectError(
      post(base + s.two().id() + "/reveal", manager, null),
      409,
      "This team solved 2 of 6 problems; at least 3 are needed for the clue."
    );
    expectError(post(base + s.zero().id() + "/reveal", manager, null), 409, "This team solved 0 of 6 problems; at least 3 are needed for the clue.");
    expectError(post(base + s.unsaved().id() + "/reveal", manager, null), 409, "This team has no saved results yet, so it is not eligible for the clue.");
    expectError(post(base + Long.MAX_VALUE + "/reveal", manager, null), 404, TEAM_NOT_FOUND);

    expectOk(setClueKey(RoomCode.PROBLEM_SOLVING, ""));
    String noKey = "Set the clue key for this room before revealing its clue.";
    expectError(post(base + s.winner().id() + "/reveal", manager, null), 409, noKey);
    expectError(post("/rooms/" + ps + "/contest-results/rounds/1/reveal", manager, null), 409, noKey);
    List<String> alerts = expectOk(get("/admin/board", admin)).json("$.alerts[*].code");
    assertTrue(alerts.contains("CONTEST_CLUE_KEY_MISSING"), alerts.toString());

    expectOk(post("/admin/teams/" + s.winner().id() + "/disqualify", admin, "{" + REASON + "}"));
    expectOk(setClueKey(RoomCode.PROBLEM_SOLVING, CLUE));
    expectError(post(base + s.winner().id() + "/reveal", manager, null), 409, "This team is disqualified; its clue cannot be revealed.");
    expectError(post("/rooms/" + ps + "/contest-results/rounds/0/reveal", manager, null), 400, "The round must be 1 to 4.");
    expectError(post("/rooms/" + ps + "/contest-results/rounds/5/reveal", manager, null), 400, "The round must be 1 to 4.");
    assertEquals(clues, count("SELECT count(*) FROM collected_clue"));
    assertEquals(0, count("SELECT count(*) FROM room_submission"));
    assertEquals(0, count("SELECT count(*) FROM contest_release"));
  }

  @Test
  void aRevealRecordsOneCorrectSubmissionAddsTheRoomClueAndFinishesTheRoomForTheTeam() {
    Round1 s = round1();
    setRound(1);
    String reveal = "/rooms/" + ps + "/contest-results/teams/" + s.tiedOne().id() + "/reveal";
    long before = revision();
    Response view = expectOk(post(reveal, manager, null));
    assertEquals(before + 1, revision(), "one revision bump");
    assertEquals(List.of(true), view.json("$.rounds[0].teams[?(@.teamName == 'B-TiedOne')].released"));
    assertEquals(1, count("SELECT count(*) FROM contest_release WHERE team_id = " + s.tiedOne().id()));
    assertEquals(1, count("SELECT count(*) FROM room_submission WHERE team_id = " + s.tiedOne().id() + " AND clue_correct AND round = 1"));
    assertEquals(1, count("SELECT count(*) FROM collected_clue WHERE team_id = " + s.tiedOne().id() + " AND room_id = " + ps + " AND source = 'ROOM'"));
    assertEquals(
      "Contest results · solved 3/6 (A, B, C) · problem points 6/12 · standing 2nd · bonus 2 · total 8/15",
      db.queryForObject("SELECT technical_output FROM room_submission WHERE team_id = ?", String.class, s.tiedOne().id())
    );
    assertEquals("Contest results released", db.queryForObject("SELECT clue_answer FROM room_submission WHERE team_id = ?", String.class, s.tiedOne().id()));
    assertEquals(true, db.queryForObject("SELECT effective_correct FROM submission_clue_verdict v JOIN room_submission s ON s.id = v.submission_id WHERE s.team_id = ?", Boolean.class, s.tiedOne().id()));
    assertEquals(
      List.of("EVENT_CONTEST_CLUE_RELEASED room:" + ps + " team:" + s.tiedOne().id() + " round:1"),
      db.queryForList("SELECT action || ' ' || target FROM audit_event WHERE action = 'EVENT_CONTEST_CLUE_RELEASED'", String.class)
    );

    // The released team sees the unlocked clue in its evidence while the round still runs; nobody else does.
    Response board = expectOk(get("/team/board", s.tiedOne().token()));
    assertEquals(List.of(true), board.json("$.evidence[?(@.roomCode == 'PROBLEM_SOLVING')].finished"));
    assertEquals(List.of(true), board.json("$.evidence[?(@.roomCode == 'PROBLEM_SOLVING')].resultRecorded"));
    assertEquals(List.of(CLUE), board.json("$.evidence[?(@.roomCode == 'PROBLEM_SOLVING')].clueValue"));
    assertEquals(1, (int) board.json("$.cluesCollected"));
    assertEquals(true, expectOk(get("/team/rooms/" + ps + "/contest", s.tiedOne().token())).json("$.released"));
    assertEquals(0, (int) expectOk(get("/team/submissions", s.tiedOne().token())).json("$.length()"), "a release is not a team receipt");
    Response other = expectOk(get("/team/board", s.winner().token()));
    assertEquals(List.of(), other.json("$.evidence[?(@.roomCode == 'PROBLEM_SOLVING' && @.clueValue != null)]"));
    assertEquals(false, expectOk(get("/team/rooms/" + ps + "/contest", s.winner().token())).json("$.released"));
    assertFalse(other.body().contains(CLUE));
  }

  @Test
  void revealingTwiceChangesNothingAndTheManagersVerdictStillGrantsAndRevokesTheClue() {
    Round1 s = round1();
    setRound(1);
    String reveal = "/rooms/" + ps + "/contest-results/teams/" + s.winner().id() + "/reveal";
    expectOk(post(reveal, manager, null));
    long revision = revision();
    long audits = count("SELECT count(*) FROM audit_event WHERE action = 'EVENT_CONTEST_CLUE_RELEASED'");
    expectOk(post(reveal, manager, null));
    expectOk(post(reveal, admin, null));
    assertEquals(revision, revision(), "a repeat is a no-op");
    assertEquals(audits, count("SELECT count(*) FROM audit_event WHERE action = 'EVENT_CONTEST_CLUE_RELEASED'"));
    assertEquals(1, count("SELECT count(*) FROM room_submission WHERE team_id = " + s.winner().id()));
    assertEquals(1, count("SELECT count(*) FROM collected_clue WHERE team_id = " + s.winner().id()));

    String submission = db.queryForObject("SELECT id::text FROM room_submission WHERE team_id = ?", String.class, s.winner().id());
    String override = "/rooms/" + ps + "/submissions/" + submission + "/clue-override";
    expectOk(put(override, manager, "{\"override\":\"REJECT\",\"version\":1}"));
    assertEquals(0, count("SELECT count(*) FROM collected_clue WHERE team_id = " + s.winner().id()), "the manual verdict revokes the clue");
    assertEquals(List.of(), expectOk(get("/team/board", s.winner().token())).json("$.evidence[?(@.roomCode == 'PROBLEM_SOLVING' && @.clueValue != null)]"));
    expectOk(put(override, admin, "{\"override\":\"ACCEPT\",\"version\":2}"));
    assertEquals(1, count("SELECT count(*) FROM collected_clue WHERE team_id = " + s.winner().id()), "and grants it again");
    expectOk(post(reveal, manager, null));
    assertEquals(1, count("SELECT count(*) FROM room_submission WHERE team_id = " + s.winner().id()), "a revoked clue is not re-released by a repeat");
  }

  @Test
  void aKeyChangeNeverReChecksAReleaseAndEditedResultsNeverChangeAClue() {
    Round1 s = round1();
    expectOk(post("/rooms/" + ps + "/contest-results/teams/" + s.tiedOne().id() + "/reveal", manager, null));
    Response key = expectOk(setClueKey(RoomCode.PROBLEM_SOLVING, "SOMETHING ELSE"));
    assertEquals(0, (int) key.json("$.recheckedSubmissions"), "the release is not a clue answer");
    assertEquals(1, count("SELECT count(*) FROM collected_clue WHERE team_id = " + s.tiedOne().id()));
    assertEquals(true, db.queryForObject("SELECT clue_correct FROM room_submission WHERE team_id = ?", Boolean.class, s.tiedOne().id()));

    // The results drop below the line afterwards: the clue stays, and the page says so.
    Response view = expectOk(save(s.tiedOne(), "[\"A\"]", "SECOND"));
    assertEquals(List.of(true), view.json("$.rounds[0].teams[?(@.teamName == 'B-TiedOne')].released"));
    assertEquals(List.of(false), view.json("$.rounds[0].teams[?(@.teamName == 'B-TiedOne')].eligible"));
    assertEquals(List.of(true), view.json("$.rounds[0].teams[?(@.teamName == 'B-TiedOne')].releasedNotEligible"));
    assertEquals(1, count("SELECT count(*) FROM collected_clue WHERE team_id = " + s.tiedOne().id()));
    assertEquals(1, count("SELECT count(*) FROM contest_release WHERE team_id = " + s.tiedOne().id()));
    assertEquals(2, (int) view.json("$.rounds[0].releasableCount"), "the winner and the other tied team are still waiting");
  }

  @Test
  void revealingARoundReleasesEveryEligibleTeamOnceAndNobodyElse() {
    Round1 s = round1();
    setRound(1);
    expectOk(post("/rooms/" + ps + "/contest-results/teams/" + s.winner().id() + "/reveal", manager, null));
    long before = revision();
    String round = "/rooms/" + ps + "/contest-results/rounds/1/reveal";
    Response view = expectOk(post(round, manager, null));
    assertEquals(before + 1, revision(), "one revision bump for the whole round");
    assertEquals(List.of(true, true, true, false, false, false), view.json("$.rounds[0].teams[*].released"));
    assertEquals(List.of(false), view.json("$.rounds[1].teams[*].released"), "round 2 is untouched");
    assertEquals(3, count("SELECT count(*) FROM contest_release"));
    assertEquals(3, count("SELECT count(*) FROM room_submission"));
    assertEquals(3, count("SELECT count(*) FROM collected_clue"));
    assertEquals(3, count("SELECT count(*) FROM audit_event WHERE action = 'EVENT_CONTEST_CLUE_RELEASED'"));

    expectOk(post(round, manager, null));
    expectOk(post(round, admin, null));
    assertEquals(before + 1, revision(), "repeating it changes nothing");
    assertEquals(3, count("SELECT count(*) FROM room_submission"));
    expectOk(post("/rooms/" + ps + "/contest-results/rounds/3/reveal", manager, null));
    assertEquals(before + 1, revision(), "a round without teams is not an error");

    // Eligible teams see the clue, the others do not.
    for (TestTeam released : List.of(s.winner(), s.tiedOne(), s.tiedTwo())) {
      assertEquals(List.of(CLUE), expectOk(get("/team/board", released.token())).json("$.evidence[?(@.roomCode == 'PROBLEM_SOLVING')].clueValue"));
    }
    for (TestTeam held : List.of(s.two(), s.zero(), s.unsaved(), s.alpha())) {
      assertEquals(List.of(), expectOk(get("/team/board", held.token())).json("$.evidence[?(@.roomCode == 'PROBLEM_SOLVING' && @.clueValue != null)]"));
    }
  }

  @Test
  void aSingleRevealAndAWholeRoundRevealRacingGiveOneSubmissionAndOneClue() throws Exception {
    Round1 s = round1();
    setRound(1);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      List<Future<Response>> calls = new ArrayList<>();
      String single = "/rooms/" + ps + "/contest-results/teams/" + s.winner().id() + "/reveal";
      String round = "/rooms/" + ps + "/contest-results/rounds/1/reveal";
      calls.add(pool.submit(() -> { go.await(); return post(single, manager, null); }));
      calls.add(pool.submit(() -> { go.await(); return post(round, admin, null); }));
      calls.add(pool.submit(() -> { go.await(); return post(single, admin, null); }));
      go.countDown();
      for (Future<Response> call : calls) expectOk(call.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
    assertEquals(3, count("SELECT count(*) FROM contest_release"));
    assertEquals(3, count("SELECT count(*) FROM room_submission"));
    assertEquals(3, count("SELECT count(*) FROM collected_clue"));
    assertEquals(1, count("SELECT count(*) FROM room_submission WHERE team_id = " + s.winner().id()));
    assertEquals(1, count("SELECT count(*) FROM collected_clue WHERE team_id = " + s.winner().id()));
  }

  @Test
  void aTeamsReleaseAfterTheFinalOpensStillRecordsInItsOwnRound() {
    Round1 s = round1();
    setRound(5);
    expectOk(post("/rooms/" + ps + "/contest-results/teams/" + s.winner().id() + "/reveal", manager, null));
    assertEquals(1, db.queryForObject("SELECT round FROM room_submission WHERE team_id = ?", Integer.class, s.winner().id()), "the round of the team's group, not the event's round 5");
    expectOk(post("/rooms/" + ps + "/contest-results/teams/" + s.alpha().id() + "/reveal", manager, null));
    assertEquals(2, db.queryForObject("SELECT round FROM room_submission WHERE team_id = ?", Integer.class, s.alpha().id()));
    assertEquals(List.of(CLUE), expectOk(get("/team/board", s.alpha().token())).json("$.evidence[?(@.roomCode == 'PROBLEM_SOLVING')].clueValue"));
  }

  // ---- what a member may see ----------------------------------------------------------------------

  @Test
  void noMemberResponseEverCarriesCountsPointsStandingBonusOrEligibilityInAnyState() {
    expectOk(put("/rooms/" + ps + "/contest-results/link", manager, "{\"url\":\"" + LINK + "\"}"));
    Round1 s = round1();
    List<TestTeam> everyone = List.of(s.winner(), s.tiedOne(), s.two(), s.zero(), s.unsaved(), s.alpha());
    List<String> paths = List.of(
      "/team/board", "/team/rooms/" + ps + "/contest", "/team/submissions", "/team/final", "/home",
      "/team/rooms/" + rooms.get(RoomCode.AI) + "/activity"
    );
    List<Runnable> states = List.of(
      () -> {},
      () -> assignRound(1),
      this::startTimer,
      () -> expectOk(post("/admin/round/timer/pause", admin, null)),
      () -> expectOk(post("/admin/round/timer/resume", admin, null)),
      () -> expectOk(post("/rooms/" + ps + "/contest-results/teams/" + s.tiedOne().id() + "/reveal", manager, null)),
      () -> expectOk(post("/rooms/" + ps + "/contest-results/rounds/1/reveal", manager, null)),
      () -> expectOk(post("/admin/round/timer/end", admin, null)),
      () -> setRound(2),
      () -> setRound(5)
    );
    int step = 0;
    for (Runnable state : states) {
      state.run();
      step++;
      for (TestTeam team : everyone) {
        for (String path : paths) {
          Response response = get(path, team.token());
          if (response.status() == 404 || response.status() == 409) continue;
          assertEquals(200, response.status(), path + " at step " + step + ": " + response.body());
          var matcher = FORBIDDEN_FIELDS.matcher(response.body());
          boolean leaked = matcher.find();
          assertFalse(leaked, "step " + step + " " + team.name() + " " + path + " leaked a field: " + response.body());
          if (!released(team)) {
            assertFalse(response.body().toUpperCase().contains(CLUE), "step " + step + " " + team.name() + " " + path + " leaked the clue before its release");
          }
        }
      }
    }
  }

  @Test
  void theClueIsNotInAnyMemberResponseBeforeItsRelease() {
    Round1 s = round1();
    setRound(1);
    for (String path : List.of("/team/board", "/team/rooms/" + ps + "/contest", "/team/submissions", "/team/final", "/home")) {
      assertFalse(expectOk(get(path, s.winner().token())).body().contains(CLUE), path);
    }
    setRound(5);
    assertFalse(expectOk(get("/team/board", s.winner().token())).body().contains(CLUE), "a finished room without a release has no clue: the recovery challenge applies");
    assertEquals(List.of(false), expectOk(get("/team/board", s.winner().token())).json("$.evidence[?(@.roomCode == 'PROBLEM_SOLVING')].clueCollected"));
    assertEquals(List.of(true), expectOk(get("/team/board", s.winner().token())).json("$.evidence[?(@.roomCode == 'PROBLEM_SOLVING')].finished"));
  }

  // ---- admin board, statements, reset -------------------------------------------------------------

  @Test
  void theAdminBoardWarnsWhileTheLinkOrTheClueKeyIsMissing() {
    List<String> codes = expectOk(get("/admin/board", admin)).json("$.alerts[*].code");
    assertTrue(codes.contains("CONTEST_LINK_MISSING"), codes.toString());
    assertFalse(codes.contains("CONTEST_CLUE_KEY_MISSING"), codes.toString());
    assertEquals(
      List.of("/event/room/" + ps + "/contest"),
      expectOk(get("/admin/board", admin)).json("$.alerts[?(@.code == 'CONTEST_LINK_MISSING')].link")
    );
    expectOk(put("/rooms/" + ps + "/contest-results/link", manager, "{\"url\":\"" + LINK + "\"}"));
    assertFalse(expectOk(get("/admin/board", admin)).<List<String>>json("$.alerts[*].code").contains("CONTEST_LINK_MISSING"));
    expectOk(setClueKey(RoomCode.PROBLEM_SOLVING, ""));
    assertTrue(expectOk(get("/admin/board", admin)).<List<String>>json("$.alerts[*].code").contains("CONTEST_CLUE_KEY_MISSING"));
    expectOk(put("/admin/rooms/" + ps + "/activity", admin, "{\"activity\":null}"));
    List<String> without = expectOk(get("/admin/board", admin)).json("$.alerts[*].code");
    assertFalse(without.contains("CONTEST_LINK_MISSING") || without.contains("CONTEST_CLUE_KEY_MISSING"), "only a contest room is warned about");
  }

  @Test
  void thePageAndTheTeamViewUseAConstantNumberOfStatementsWhateverTheNumberOfTeams() {
    expectOk(put("/rooms/" + ps + "/contest-results/link", manager, "{\"url\":\"" + LINK + "\"}"));
    TestTeam first = team("First", GroupCode.B);
    team("Second", GroupCode.A);
    setRound(1);
    List<String> fewPage = statements("/rooms/" + ps + "/contest-results", manager);
    List<String> fewTeam = statements("/team/rooms/" + ps + "/contest", first.token());
    teams(3);
    expectOk(save(first, "[\"A\",\"B\",\"C\"]", "THIRD"));
    assertEquals(14, ((List<?>) expectOk(get("/rooms/" + ps + "/contest-results", manager)).json("$.rounds[*].teams[*]")).size());
    List<String> manyPage = statements("/rooms/" + ps + "/contest-results", manager);
    List<String> manyTeam = statements("/team/rooms/" + ps + "/contest", first.token());
    assertEquals(fewPage.size(), manyPage.size(), "few: " + fewPage + "\nmany: " + manyPage);
    assertEquals(fewTeam.size(), manyTeam.size(), "few: " + fewTeam + "\nmany: " + manyTeam);
    assertEquals(4, manyPage.size(), manyPage.toString());
    assertEquals(6, manyTeam.size(), manyTeam.toString());
    System.out.println("CONTEST STATEMENTS organizer page: 2 teams = " + fewPage.size() + ", 14 teams = " + manyPage.size() + "; team view: 2 teams = " + fewTeam.size() + ", 14 teams = " + manyTeam.size());
  }

  @Test
  void aTeamResetDeletesItsResultAndReleaseAfterASnapshotThatHoldsThemAndAnEventResetDeletesAllButKeepsTheLink() {
    expectOk(put("/rooms/" + ps + "/contest-results/link", manager, "{\"url\":\"" + LINK + "\"}"));
    Round1 s = round1();
    expectOk(post("/rooms/" + ps + "/contest-results/rounds/1/reveal", manager, null));
    assertEquals(6, count("SELECT count(*) FROM contest_result"));
    assertEquals(3, count("SELECT count(*) FROM contest_release"));

    Response reset = expectOk(post("/admin/reset/team/" + s.winner().id(), admin, "{" + REASON + "}"));
    long snapshot = ((Number) reset.json("$.snapshotId")).longValue();
    assertEquals(1, snapshotRows(snapshot, "contest_result"));
    assertEquals(1, snapshotRows(snapshot, "contest_release"));
    assertEquals(5, count("SELECT count(*) FROM contest_result"));
    assertEquals(2, count("SELECT count(*) FROM contest_release"));
    Response download = call(HttpMethod.GET, "/admin/snapshots/" + snapshot + "/download", admin, null, null);
    assertTrue(download.body().contains("contest_result") && download.body().contains("contest_release"));
    Response view = expectOk(get("/rooms/" + ps + "/contest-results", manager));
    assertEquals(List.of(false), view.json("$.rounds[0].teams[?(@.teamName == 'B-Winner')].saved"));
    assertEquals(List.of(8), view.json("$.rounds[0].teams[?(@.teamName == 'B-TiedOne')].total"), "the others keep their scores");

    String body = "{\"confirmText\":\"RESET\",\"password\":\"%s\",%s}".formatted(PASSWORD, REASON);
    Response all = expectOk(post("/admin/reset/event", admin, body));
    long eventSnapshot = ((Number) all.json("$.snapshotId")).longValue();
    assertEquals(5, snapshotRows(eventSnapshot, "contest_result"));
    assertEquals(2, snapshotRows(eventSnapshot, "contest_release"));
    assertEquals(0, count("SELECT count(*) FROM contest_result"));
    assertEquals(0, count("SELECT count(*) FROM contest_release"));
    assertEquals(LINK, expectOk(get("/rooms/" + ps + "/contest-results", manager)).json("$.contestUrl"), "the link is content: it stays");
  }

  @Test
  void aSnapshotTakenByHandCoversTheContestTablesToo() {
    Round1 s = round1();
    expectOk(post("/rooms/" + ps + "/contest-results/teams/" + s.winner().id() + "/reveal", manager, null));
    Response snapshot = expectOk(post("/admin/snapshots", admin, "{" + REASON + "}"));
    long id = ((Number) snapshot.json("$.snapshotId")).longValue();
    assertEquals(6, snapshotRows(id, "contest_result"));
    assertEquals(1, snapshotRows(id, "contest_release"));
  }

  @Test
  void releasesAreAppendOnly() {
    Round1 s = round1();
    expectOk(post("/rooms/" + ps + "/contest-results/teams/" + s.winner().id() + "/reveal", manager, null));
    org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> db.update("UPDATE contest_release SET released_at = now()"));
    org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> db.update("DELETE FROM contest_release"));
    assertEquals(1, count("SELECT count(*) FROM contest_release"));
  }

  // ---- helpers ------------------------------------------------------------------------------------

  private Response save(TestTeam team, String problemsJson, String standing) {
    return put(
      "/rooms/" + ps + "/contest-results/teams/" + team.id(),
      manager,
      "{\"problems\":%s,\"standing\":\"%s\"}".formatted(problemsJson, standing)
    );
  }

  private long count(String sql) {
    return db.queryForObject(sql, Long.class);
  }

  private boolean released(TestTeam team) {
    return count("SELECT count(*) FROM contest_release WHERE team_id = " + team.id()) > 0;
  }

  private int snapshotRows(long snapshotId, String table) {
    return db.queryForObject("SELECT jsonb_array_length(data -> ?) FROM event_snapshot WHERE id = ?", Integer.class, table, snapshotId);
  }

  private List<String> statements(String path, String token) {
    expectOk(get(path, token));
    clearInvocations(spiedJdbc);
    expectOk(get(path, token));
    List<String> statements = new ArrayList<>();
    for (Invocation invocation : mockingDetails(spiedJdbc).getInvocations()) {
      Object[] args = invocation.getArguments();
      if (args.length == 0 || !(args[0] instanceof String sql) || sql.contains("auth_session")) continue;
      if (statements.isEmpty() || !statements.getLast().equals(sql)) statements.add(sql);
    }
    return statements;
  }
}
