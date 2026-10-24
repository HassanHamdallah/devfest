package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

/** The admin's manual round timer, the phase rules for submissions, and the managers' arrival check. */
@Import(ManualClock.Config.class)
class EventRoundTimerTest extends EventIntegrationTest {

  /** A short default round keeps every test well inside the 15-minute access token lifetime. */
  private static final int DEFAULT_SECONDS = 5 * 60;

  @Autowired
  private ManualClock clock;

  private TestTeam alpha;

  @BeforeEach
  void prepare() {
    clock.reset();
    loadContent();
    alpha = team("Alpha", GroupCode.A);
    expectOk(put("/admin/settings", admin, "{\"roundMinutes\":5}"));
  }

  @Test
  void nextRoundAssignsRoomsWithoutStartingTheTimer() {
    assignRound(1);
    openRoom(RoomCode.AI);
    assertEquals("ASSIGNED", state("phase"));
    assertEquals(DEFAULT_SECONDS, (int) state("remainingSeconds"));
    assertEquals(DEFAULT_SECONDS, (int) state("durationSeconds"));
    clock.advance(Duration.ofMinutes(10));
    assertEquals(DEFAULT_SECONDS, (int) state("remainingSeconds"), "no countdown while assigned");
    assertEquals("ASSIGNED", expectOk(get("/team/board", alpha.token())).json("$.clock.phase"));
    expectError(submitAi("a"), 409, "The timer has not started yet.");
    assertNull(db.queryForObject("SELECT round_started_at FROM event_state", java.sql.Timestamp.class));
  }

  @Test
  void submissionsStopWhenTimeIsUpButExactRetriesKeepTheirReceipt() {
    assignRound(1);
    openRoom(RoomCode.AI);
    assertEquals("RUNNING", expectOk(post("/admin/round/timer/start", admin, null)).json("$.phase"));
    UUID key = UUID.randomUUID();
    String body = "{\"technicalOutput\":\"o\",\"clueAnswer\":\"WHITE VAN\"}";
    String receipt = expectOk(teamPost(alpha, "/team/rooms/" + rooms.get(RoomCode.AI) + "/submissions", key, body)).json("$.id");

    clock.advance(Duration.ofSeconds(DEFAULT_SECONDS + 1));
    expectError(submitAi("late"), 409, "Time is up for this round.");
    Response retry = expectOk(teamPost(alpha, "/team/rooms/" + rooms.get(RoomCode.AI) + "/submissions", key, body));
    assertEquals(receipt, retry.json("$.id"));
    assertEquals("TIME_UP", state("phase"));
    assertEquals(0, (int) state("remainingSeconds"));
  }

  @Test
  void pausingFreezesTheRemainingTimeAndResumeContinuesFromIt() {
    setRound(1);
    openRoom(RoomCode.AI);
    clock.advance(Duration.ofSeconds(100));
    assertEquals(DEFAULT_SECONDS - 100, (int) expectOk(post("/admin/round/timer/pause", admin, null)).json("$.remainingSeconds"));
    expectError(submitAi("x"), 409, "The round is paused.");
    clock.advance(Duration.ofMinutes(10));
    assertEquals(DEFAULT_SECONDS - 100, (int) state("remainingSeconds"));
    assertEquals(DEFAULT_SECONDS - 100, (int) expectOk(get("/team/board", alpha.token())).json("$.clock.remainingSeconds"));

    expectOk(post("/admin/round/timer/resume", admin, null));
    clock.advance(Duration.ofSeconds(10));
    assertEquals(DEFAULT_SECONDS - 110, (int) state("remainingSeconds"));
    expectOk(submitAi("x"));
  }

  @Test
  void addingTimeAfterTimeIsUpReopensSubmissions() {
    setRound(1);
    openRoom(RoomCode.AI);
    clock.advance(Duration.ofSeconds(DEFAULT_SECONDS + 30));
    expectError(submitAi("x"), 409, "Time is up for this round.");
    Response added = expectOk(adjust(300, UUID.randomUUID()));
    assertEquals("RUNNING", added.json("$.phase"));
    assertEquals(300, (int) added.json("$.remainingSeconds"));
    expectOk(submitAi("x"));
  }

  @Test
  void repeatedActionsAreSafe() {
    setRound(1);
    expectOk(post("/admin/round/timer/pause", admin, null));
    expectOk(post("/admin/round/timer/pause", admin, null));
    assertEquals(1, audits("EVENT_TIMER_PAUSED"));
    expectError(post("/admin/round/timer/start", admin, null), 409, "The timer is paused. Resume it instead.");

    UUID key = UUID.randomUUID();
    expectOk(adjust(60, key));
    assertEquals(DEFAULT_SECONDS + 60, (int) expectOk(adjust(60, key)).json("$.durationSeconds"), "the same key applies once");
    assertEquals(1, audits("EVENT_TIME_ADDED"));
    assertEquals("round:1 +60s", db.queryForObject("SELECT target FROM audit_event WHERE action = 'EVENT_TIME_ADDED'", String.class));

    Response cut = expectOk(adjust(-3600, UUID.randomUUID()));
    assertEquals("TIME_UP", cut.json("$.phase"));
    assertEquals(0, (int) cut.json("$.remainingSeconds"));
    expectOk(post("/admin/round/timer/end", admin, null));
    assertEquals(0, audits("EVENT_ROUND_ENDED"), "already time up: nothing to end");
    assertEquals(400, adjust(0, UUID.randomUUID()).status());
    assertEquals(400, call(HttpMethod.POST, "/admin/round/timer/adjust", admin, "{\"deltaSeconds\":60}", null).status());

    assignRound(5);
    expectError(post("/admin/round/timer/start", admin, null), 409, "No round is running.");
    assertEquals(403, post("/admin/round/timer/start", managers.get(RoomCode.AI), null).status());
  }

  @Test
  void changingTheDefaultLengthAffectsOnlyLaterRounds() {
    setRound(1);
    expectOk(put("/admin/settings", admin, "{\"roundMinutes\":10}"));
    assertEquals(DEFAULT_SECONDS, (int) state("durationSeconds"));
    assignRound(2);
    assertEquals(600, (int) state("durationSeconds"));
  }

  @Test
  void managersConfirmArrivalOnlyForTheirRoomWhileAssigned() {
    assignRound(1);
    String path = "/rooms/" + rooms.get(RoomCode.AI) + "/arrival";
    expectError(put(path, managers.get(RoomCode.LINUX), "{\"ready\":true}"), 404, "Room not found.");
    assertEquals(403, put(path, alpha.token(), "{\"ready\":true}").status());

    Response ready = expectOk(put(path, managers.get(RoomCode.AI), "{\"ready\":true}"));
    assertEquals(true, ready.json("$.ready"));
    Response board = expectOk(get("/admin/board", admin));
    assertEquals(List.of(true), board.json("$.rooms[?(@.code == 'AI')].arrival.ready"));
    assertEquals(List.of(false), board.json("$.rooms[?(@.code == 'LINUX')].arrival.ready"));
    Response room = expectOk(get("/rooms/" + rooms.get(RoomCode.AI) + "/board", managers.get(RoomCode.AI)));
    assertEquals(true, room.json("$.arrival.ready"));
    assertEquals(List.of("Alpha"), room.json("$.teamsExpectedNow"));

    assertEquals(false, expectOk(put(path, managers.get(RoomCode.AI), "{\"ready\":false}")).json("$.ready"));
    expectOk(put(path, managers.get(RoomCode.AI), "{\"ready\":true}"));
    expectOk(put(path, managers.get(RoomCode.AI), "{\"ready\":true}"));
    assertEquals(2, audits("EVENT_ROOM_READY"), "a repeated confirmation changes nothing");

    startTimer();
    expectError(put(path, managers.get(RoomCode.AI), "{\"ready\":false}"), 409, "Arrival can only be confirmed before the round's timer starts.");
    assignRound(2);
    assertEquals(List.of(false, false, false, false), expectOk(get("/admin/board", admin)).json("$.rooms[*].arrival.ready"));
  }

  @Test
  void onlyTimerAndArrivalActionsBumpTheRevision() {
    assignRound(1);
    long revision = revision();
    expectOk(put("/rooms/" + rooms.get(RoomCode.AI) + "/arrival", managers.get(RoomCode.AI), "{\"ready\":true}"));
    startTimer();
    expectOk(post("/admin/round/timer/pause", admin, null));
    expectOk(post("/admin/round/timer/resume", admin, null));
    expectOk(adjust(60, UUID.randomUUID()));
    expectOk(post("/admin/round/timer/end", admin, null));
    assertEquals(revision + 6, revision());
    clock.advance(Duration.ofMinutes(5));
    expectOk(get("/admin/board", admin));
    expectOk(get("/rooms/" + rooms.get(RoomCode.AI) + "/board", managers.get(RoomCode.AI)));
    expectOk(get("/team/board", alpha.token()));
    expectOk(get("/revision", alpha.token()));
    assertEquals(revision + 6, revision());
  }

  /** A field of the admin board's state (round, phase, remainingSeconds, durationSeconds, …). */
  private <T> T state(String field) {
    return expectOk(get("/admin/board", admin)).json("$.state." + field);
  }

  private Response submitAi(String clue) {
    return submit(alpha, RoomCode.AI, UUID.randomUUID(), "o", clue);
  }

  private Response adjust(int deltaSeconds, UUID key) {
    return call(HttpMethod.POST, "/admin/round/timer/adjust", admin, "{\"deltaSeconds\":" + deltaSeconds + "}", key.toString());
  }

  private int audits(String action) {
    return db.queryForObject("SELECT count(*) FROM audit_event WHERE action = ?", Integer.class, action);
  }
}
