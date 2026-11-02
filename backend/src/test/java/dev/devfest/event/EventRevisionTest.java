package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** D14: every visible write bumps the revision once; reads, no-op writes and replays never do. */
class EventRevisionTest extends EventIntegrationTest {

  private TestTeam alpha;

  @BeforeEach
  void createTeam() {
    loadContent();
    alpha = team("Alpha", GroupCode.A);
  }

  @Test
  void writesBumpTheRevision() {
    assertBumps(() -> setRound(1));
    assertBumps(() -> openRoom(RoomCode.AI));
    assertBumps(() -> expectOk(submit(alpha, RoomCode.AI, UUID.randomUUID(), "o", "WHITE VAN")));
    String judgement = "/rooms/" + rooms.get(RoomCode.AI) + "/judgements/" + alpha.id();
    assertBumps(() -> expectOk(put(judgement, managers.get(RoomCode.AI), EventJudgingTest.judgement("PASS", 0, "", 0))));
    String penalty = "{\"teamId\":%d,\"kind\":\"HINT\",\"note\":\"\"}".formatted(alpha.id());
    assertBumps(() -> expectOk(post("/rooms/" + rooms.get(RoomCode.AI) + "/penalties", managers.get(RoomCode.AI), penalty)));
    assertBumps(() -> expectOk(saveRoomContent(RoomCode.LINUX, admin, "WEST TOWER", "P", "A")));
    assertBumps(() -> expectOk(put("/admin/publication", admin, "{\"published\":true}")));
  }

  @Test
  void readsNoOpWritesAndReplaysDoNotBumpTheRevision() {
    setRound(1);
    openRoom(RoomCode.AI);
    UUID key = UUID.randomUUID();
    expectOk(submit(alpha, RoomCode.AI, key, "o", "WHITE VAN"));
    String judgement = "/rooms/" + rooms.get(RoomCode.AI) + "/judgements/" + alpha.id();
    String pass = EventJudgingTest.judgement("PASS", 0, "", 0);
    expectOk(put(judgement, managers.get(RoomCode.AI), pass));
    long before = revision();

    expectOk(get("/revision", alpha.token()));
    expectOk(get("/team/board", alpha.token()));
    expectOk(get("/team/final", alpha.token()));
    expectOk(get("/rooms/" + rooms.get(RoomCode.AI) + "/board", managers.get(RoomCode.AI)));
    expectOk(get("/admin/board", admin));
    expectOk(get("/admin/organization", admin));
    expectOk(get("/admin/content", admin));
    expectOk(get("/results", admin));
    setRound(1);
    openRoom(RoomCode.AI);
    expectOk(put(judgement, managers.get(RoomCode.AI), pass));
    expectOk(submit(alpha, RoomCode.AI, key, "o", "WHITE VAN"));
    expectOk(put("/admin/publication", admin, "{\"published\":false}"));

    assertEquals(before, revision());
    assertEquals(before, ((Number) expectOk(get("/revision", alpha.token())).json("$.revision")).longValue());
  }

  private void assertBumps(Runnable write) {
    long before = revision();
    write.run();
    assertEquals(before + 1, revision());
  }
}
