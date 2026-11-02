package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Penalties (D9): who may add and void them, automatic kinds, and voided entries never counting. */
class EventPenaltyTest extends EventIntegrationTest {

  private TestTeam alpha;
  private String aiManager;

  @BeforeEach
  void createTeam() {
    loadContent();
    alpha = team("Alpha", GroupCode.A);
    aiManager = managers.get(RoomCode.AI);
  }

  @Test
  void managerIsLimitedToTheirRoomAndToManualKinds() {
    expectError(addPenalty(aiManager, RoomCode.LINUX, "HINT"), 404, "Room not found.");
    expectError(addPenalty(aiManager, RoomCode.AI, "RECOVERY"), 400, "This penalty kind is added automatically.");
    expectError(addPenalty(aiManager, RoomCode.AI, "WRONG_ACCUSATION"), 400, "This penalty kind is added automatically.");
    Response cheating = expectOk(addPenalty(aiManager, RoomCode.AI, "CHEATING"));
    assertEquals(5, (int) cheating.json("$.minutes"));
    assertEquals(2, (int) expectOk(addPenalty(admin, RoomCode.LINUX, "MINOR_VIOLATION")).json("$.minutes"));
    long linuxPenalty = db.queryForObject("SELECT id FROM penalty WHERE kind = 'MINOR_VIOLATION'", Long.class);
    expectError(voidPenalty(aiManager, linuxPenalty), 404, "Penalty not found.");
  }

  @Test
  void voidedPenaltiesAreExcludedFromEveryTotal() {
    long hint = ((Number) expectOk(addPenalty(aiManager, RoomCode.AI, "HINT")).json("$.id")).longValue();
    expectOk(addPenalty(aiManager, RoomCode.AI, "PROHIBITED_TOOL"));
    expectOk(voidPenalty(aiManager, hint));
    expectError(voidPenalty(aiManager, hint), 409, "This penalty is already voided.");
    Response board = expectOk(get("/rooms/" + rooms.get(RoomCode.AI) + "/board", aiManager));
    assertEquals(5, (int) board.json("$.teams[0].activePenaltyMinutes"));
    assertEquals(2, (int) board.json("$.teams[0].penalties.length()"));
    assertEquals(5, (int) expectOk(get("/team/final", alpha.token())).json("$.penaltyMinutes"));
    Response ranking = expectOk(get("/admin/board", admin));
    assertEquals(5, (int) ranking.json("$.ranking[0].penaltyMinutes"));
    assertEquals(1, (int) ranking.json("$.ranking[0].penaltyEntries"));
  }

  @Test
  void hintPenaltyMismatchFlagsDisagreementWithTheHintCount() {
    String judgement = "/rooms/" + rooms.get(RoomCode.AI) + "/judgements/" + alpha.id();
    expectOk(put(judgement, aiManager, EventJudgingTest.judgement("PENDING", 1, "", 0)));
    assertEquals(true, mismatch());
    expectOk(addPenalty(aiManager, RoomCode.AI, "HINT"));
    assertEquals(false, mismatch());
  }

  @Test
  void wrongAccusationPenaltyIsVoidableByAdminOnlyAndRecoveryPenaltyIsAddedOnce() {
    collectClues(alpha, RoomCode.AI, RoomCode.PROBLEM_SOLVING, RoomCode.LINUX);
    setRound(5);
    openFinal();
    expectOk(recover(alpha, UUID.randomUUID(), "wrong"));
    expectOk(recover(alpha, UUID.randomUUID(), RECOVERY_ANSWERS.get(RoomCode.HARDWARE)));
    expectError(
      recover(alpha, UUID.randomUUID(), RECOVERY_ANSWERS.get(RoomCode.HARDWARE)),
      409,
      "Recovery is not available for your team."
    );
    assertEquals(1, penaltyCount("RECOVERY"));
    expectOk(accuse(alpha, UUID.randomUUID(), "Mazen Haddad"));
    long wrong = db.queryForObject("SELECT id FROM penalty WHERE kind = 'WRONG_ACCUSATION'", Long.class);
    expectError(voidPenalty(aiManager, wrong), 404, "Penalty not found.");
    expectOk(voidPenalty(admin, wrong));
    assertEquals(5, (int) expectOk(get("/team/final", alpha.token())).json("$.penaltyMinutes"));
  }

  private boolean mismatch() {
    return expectOk(get("/rooms/" + rooms.get(RoomCode.AI) + "/board", aiManager)).json("$.teams[0].hintPenaltyMismatch");
  }

  private int penaltyCount(String kind) {
    return db.queryForObject("SELECT count(*) FROM penalty WHERE kind = ?", Integer.class, kind);
  }

  private Response addPenalty(String token, RoomCode room, String kind) {
    String body = "{\"teamId\":%d,\"kind\":\"%s\",\"note\":\"test\"}".formatted(alpha.id(), kind);
    return post("/rooms/" + rooms.get(room) + "/penalties", token, body);
  }

  private Response voidPenalty(String token, long penaltyId) {
    return post("/penalties/" + penaltyId + "/void", token, "{\"reason\":\"entered by mistake\"}");
  }
}
