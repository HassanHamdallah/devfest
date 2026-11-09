package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Manager judging (D7, D8): optimistic versions, no-op retries, one history row per real change. */
class EventJudgingTest extends EventIntegrationTest {

  private TestTeam alpha;
  private String path;
  private String aiManager;

  @BeforeEach
  void createTeam() {
    loadContent();
    alpha = team("Alpha", GroupCode.A);
    path = "/rooms/" + rooms.get(RoomCode.AI) + "/judgements/" + alpha.id();
    aiManager = managers.get(RoomCode.AI);
  }

  @Test
  void staleVersionIsAConflictAndIdenticalRetryIsANoOp() {
    Response first = expectOk(put(path, aiManager, judgement("PASS", "good", 0)));
    assertEquals(1, (int) first.json("$.version"));
    long revisionAfterWrite = revision();
    Response retry = expectOk(put(path, aiManager, judgement("PASS", "good", 0)));
    assertEquals(1, (int) retry.json("$.version"));
    assertEquals(revisionAfterWrite, revision());
    expectError(
      put(path, aiManager, judgement("FAIL", "good", 0)),
      409,
      "Someone else changed this judgement. Reload and try again."
    );
    assertEquals(1, historyCount());
  }

  @Test
  void everyRealChangeWritesExactlyOneHistoryRow() {
    expectOk(put(path, aiManager, judgement("PASS", "", 0)));
    expectOk(put(path, aiManager, judgement("FAIL", "", 1)));
    expectOk(put(path, admin, judgement("FAIL", "second look", 2)));
    assertEquals(3, historyCount());
    Map<String, Object> last = db.queryForMap(
      "SELECT old_status, new_status, note, reviewer_id, version FROM room_judgement_history ORDER BY version DESC LIMIT 1"
    );
    assertEquals("FAIL", last.get("old_status"));
    assertEquals("FAIL", last.get("new_status"));
    assertEquals("second look", last.get("note"));
    assertEquals(3L, last.get("version"));
    Map<String, Object> firstRow = db.queryForMap(
      "SELECT old_status, reviewer_id FROM room_judgement_history WHERE version = 1"
    );
    assertEquals(null, firstRow.get("old_status"));
    assertEquals(managerIds.get(RoomCode.AI), firstRow.get("reviewer_id"));
  }

  @Test
  void closingARoomDoesNotChangeJudgements() {
    setRound(1);
    openRoom(RoomCode.AI);
    expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), "output", "WHITE VAN"));
    expectOk(put(path, aiManager, judgement("PASS", "", 0)));
    closeRoom(RoomCode.AI);
    Response board = expectOk(get("/rooms/" + rooms.get(RoomCode.AI) + "/board", aiManager));
    assertEquals("PASS", board.json("$.teams[0].judgement.status"));
    assertEquals(1, (int) board.json("$.counters.passed"));
    assertEquals("PASSED", expectOk(get("/team/board", alpha.token())).json("$.rooms[0].myStatus"));
  }

  @Test
  void failDoesNotRemoveACollectedClue() {
    setRound(1);
    openRoom(RoomCode.AI);
    expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), "output", "WHITE VAN"));
    expectOk(put(path, aiManager, judgement("FAIL", "", 0)));
    Response board = expectOk(get("/team/board", alpha.token()));
    assertEquals("FAILED", board.json("$.rooms[0].myStatus"));
    assertEquals(1, (int) board.json("$.cluesCollected"));
  }

  private int historyCount() {
    return db.queryForObject("SELECT count(*) FROM room_judgement_history", Integer.class);
  }

  static String judgement(String status, String note, long version) {
    return "{\"status\":\"%s\",\"note\":%s,\"version\":%d}".formatted(status, quote(note), version);
  }
}
