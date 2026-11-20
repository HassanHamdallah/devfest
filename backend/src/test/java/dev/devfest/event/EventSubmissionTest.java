package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Room submissions: rotation and open/closed rules (D4, D5), clue checking (D6), idempotent receipts. */
class EventSubmissionTest extends EventIntegrationTest {

  private TestTeam alpha;

  @BeforeEach
  void createTeam() {
    loadContent();
    alpha = team("Alpha", GroupCode.A);
  }

  @Test
  void everyGroupIsScheduledIntoTheRotationRoomEachRound() {
    List<TestTeam> oneTeamPerGroup = List.of(alpha, team("Bravo", GroupCode.B), team("Charlie", GroupCode.C), team("Delta", GroupCode.D));
    for (int round = 1; round <= 4; round++) {
      setRound(round);
      for (TestTeam team : oneTeamPerGroup) {
        Response board = expectOk(get("/team/board", team.token()));
        assertEquals(roomFor(team.group(), round).name(), board.json("$.scheduledRoom.code"), team.group() + " round " + round);
      }
    }
    setRound(5);
    assertNull(expectOk(get("/team/board", alpha.token())).json("$.scheduledRoom"));
  }

  @Test
  void submissionIsRejectedOutsideTheScheduledOpenRoom() {
    expectError(submit(alpha, RoomCode.AI, UUID.randomUUID(), "out", "x"), 409, "Rooms are not running right now.");
    setRound(1);
    expectError(submit(alpha, RoomCode.AI, UUID.randomUUID(), "out", "x"), 409, "This room is closed.");
    openRoom(RoomCode.LINUX);
    expectError(
      submit(alpha, RoomCode.LINUX, UUID.randomUUID(), "out", "x"),
      409,
      "This is not your team's room in the current round."
    );
    openRoom(RoomCode.AI);
    expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), "out", "x"));
    setRound(5);
    expectError(submit(alpha, RoomCode.AI, UUID.randomUUID(), "out", "x"), 409, "Rooms are not running right now.");
    assertEquals(1, submissionCount());
  }

  @Test
  void unknownRoomIsNotFound() {
    setRound(1);
    Response response = teamPost(alpha, "/team/rooms/99999/submissions", UUID.randomUUID(), "{\"technicalOutput\":\"o\",\"clueAnswer\":\"c\"}");
    expectError(response, 404, "Room not found.");
  }

  @Test
  void sameKeyReturnsTheOriginalReceiptEvenAfterTheRoomCloses() {
    setRound(1);
    openRoom(RoomCode.AI);
    UUID key = UUID.randomUUID();
    Response first = expectOk(submit(alpha, RoomCode.AI, key, "output", "white van"));
    assertEquals(first.body(), expectOk(submit(alpha, RoomCode.AI, key, "output", "white van")).body());
    closeRoom(RoomCode.AI);
    assertEquals(first.body(), expectOk(submit(alpha, RoomCode.AI, key, "output", "white van")).body());
    setRound(2);
    assertEquals(first.body(), expectOk(submit(alpha, RoomCode.AI, key, "output", "white van")).body());
    assertEquals(1, submissionCount());
  }

  @Test
  void sameKeyWithDifferentBodyIsAConflict() {
    setRound(1);
    openRoom(RoomCode.AI);
    UUID key = UUID.randomUUID();
    expectOk(submit(alpha, RoomCode.AI, key, "output", "white van"));
    expectError(
      submit(alpha, RoomCode.AI, key, "other output", "white van"),
      409,
      "This Idempotency-Key was already used for a different request."
    );
    assertEquals(1, submissionCount());
  }

  @Test
  void parallelSubmissionsWithDistinctKeysAreAllKept() throws Exception {
    setRound(1);
    openRoom(RoomCode.AI);
    int count = 20;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(count);
    try {
      List<Future<Response>> futures = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        String output = "parallel " + i;
        futures.add(pool.submit(() -> {
          start.await();
          return submit(alpha, RoomCode.AI, UUID.randomUUID(), output, "wrong");
        }));
      }
      start.countDown();
      Set<String> receipts = new HashSet<>();
      for (Future<Response> future : futures) receipts.add(expectOk(future.get()).json("$.id"));
      assertEquals(count, receipts.size());
    } finally {
      pool.shutdownNow();
    }
    assertEquals(count, submissionCount());
  }

  @Test
  void clueAnswersAreNormalizedButPunctuationMustMatch() {
    setRound(1);
    openRoom(RoomCode.AI);
    assertClue(RoomCode.AI, "WHITE-VAN", false);
    assertClue(RoomCode.AI, "  white   van ", true);
    TestTeam delta = team("Delta", GroupCode.D);
    openRoom(RoomCode.HARDWARE);
    assertEquals(false, expectOk(submit(delta, RoomCode.HARDWARE, UUID.randomUUID(), "o", "+970 59 881")).json("$.clueCorrect"));
    assertEquals(true, expectOk(submit(delta, RoomCode.HARDWARE, UUID.randomUUID(), "o", " +970-59-881 ")).json("$.clueCorrect"));
  }

  @Test
  void aCollectedClueStaysCollectedAfterAWrongResubmission() {
    setRound(1);
    openRoom(RoomCode.AI);
    assertClue(RoomCode.AI, "WHITE VAN", true);
    assertClue(RoomCode.AI, "wrong", false);
    Response board = expectOk(get("/team/board", alpha.token()));
    assertEquals(1, (int) board.json("$.cluesCollected"));
    assertEquals(true, board.json("$.rooms[0].clueCollected"));
    assertEquals(false, board.json("$.rooms[0].lastReceipt.clueCorrect"));
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM collected_clue", Integer.class));
  }

  @Test
  void theSixtyFirstTeamPostWithinAMinuteIsRateLimited() {
    setRound(1);
    openRoom(RoomCode.AI);
    for (int i = 0; i < 60; i++) expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), "o" + i, "x"));
    expectError(submit(alpha, RoomCode.AI, UUID.randomUUID(), "o", "x"), 429, "Too many attempts. Wait a moment and try again.");
    assertEquals(60, submissionCount());
  }

  private void assertClue(RoomCode room, String clue, boolean correct) {
    assertEquals(correct, expectOk(submit(alpha, room, UUID.randomUUID(), "output", clue)).json("$.clueCorrect"), clue);
  }

  private int submissionCount() {
    return db.queryForObject("SELECT count(*) FROM room_submission", Integer.class);
  }
}
