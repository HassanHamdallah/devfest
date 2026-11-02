package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The final (D10, D11, D13): stages by collected clues, recovery, accusations and the content lock. */
class EventFinalTest extends EventIntegrationTest {

  private TestTeam alpha;

  @BeforeEach
  void createTeam() {
    loadContent();
    alpha = team("Alpha", GroupCode.A);
  }

  @Test
  void stageFollowsCollectedCluesAndTheFinalState() {
    TestTeam bravo = team("Bravo", GroupCode.B);
    TestTeam charlie = team("Charlie", GroupCode.C);
    collectClues(alpha, RoomCode.values());
    collectClues(bravo, RoomCode.AI, RoomCode.LINUX, RoomCode.HARDWARE);
    collectClues(charlie, RoomCode.AI, RoomCode.LINUX);
    assertEquals("LOCKED", stage(alpha));
    setRound(5);
    openFinal();
    assertEquals("MESSAGE", stage(alpha));
    assertEquals("RECOVERY", stage(bravo));
    assertEquals("NOT_ENOUGH_CLUES", stage(charlie));
    Response notEnough = expectOk(get("/team/final", charlie.token()));
    assertNull(notEnough.json("$.encryptedMessage"));
    assertEquals(0, (int) notEnough.json("$.suspects.length()"));
    expectOk(accuse(alpha, UUID.randomUUID(), CORRECT_SUSPECT));
    expectOk(post("/admin/final/close", admin, null));
    assertEquals("FINISHED", stage(alpha));
    assertEquals("CLOSED", stage(bravo));
  }

  @Test
  void recoveryOffersOnlyTheMissingRoomAndGrantsItsClueWithOnePenalty() {
    collectClues(alpha, RoomCode.AI, RoomCode.PROBLEM_SOLVING, RoomCode.LINUX);
    setRound(5);
    openFinal();
    Response view = expectOk(get("/team/final", alpha.token()));
    assertEquals("HARDWARE", view.json("$.recovery.roomCode"));
    assertEquals("Recovery prompt for HARDWARE", view.json("$.recovery.prompt"));
    assertFalse(view.body().contains("Recovery prompt for AI"));
    assertNull(view.json("$.encryptedMessage"));
    for (int i = 0; i < 3; i++) {
      Response wrong = expectOk(recover(alpha, UUID.randomUUID(), "wrong " + i));
      assertEquals(false, wrong.json("$.correct"));
      assertEquals("RECOVERY", wrong.json("$.stage"));
    }
    assertEquals(0, count("SELECT count(*) FROM penalty"));
    Response correct = expectOk(recover(alpha, UUID.randomUUID(), "  eight   bits "));
    assertEquals(true, correct.json("$.correct"));
    assertEquals("MESSAGE", correct.json("$.stage"));
    assertEquals("RECOVERY", db.queryForObject(
      "SELECT source FROM collected_clue WHERE team_id = ? AND room_id = ?",
      String.class,
      alpha.id(),
      rooms.get(RoomCode.HARDWARE)
    ));
    assertEquals(1, count("SELECT count(*) FROM penalty WHERE kind = 'RECOVERY' AND minutes = 5"));
    assertEquals(4, count("SELECT count(*) FROM recovery_attempt"));
    Response message = expectOk(get("/team/final", alpha.token()));
    assertEquals("WKH SUDFWLFH FOXH", message.json("$.encryptedMessage"));
    assertEquals(6, (int) message.json("$.suspects.length()"));
  }

  @Test
  void wrongAccusationCostsThreeMinutesAndTheFirstCorrectOneFinishes() {
    collectClues(alpha, RoomCode.values());
    setRound(5);
    openFinal();
    Response wrong = expectOk(accuse(alpha, UUID.randomUUID(), "Farid Mansour"));
    assertEquals(false, wrong.json("$.correct"));
    assertEquals("MESSAGE", wrong.json("$.stage"));
    assertFalse(wrong.body().contains(CORRECT_SUSPECT));
    assertFalse(wrong.body().toLowerCase().contains("suspect"), wrong.body());
    assertEquals(3, (int) expectOk(get("/team/final", alpha.token())).json("$.penaltyMinutes"));
    UUID key = UUID.randomUUID();
    Response correct = expectOk(accuse(alpha, key, CORRECT_SUSPECT));
    assertEquals(true, correct.json("$.correct"));
    assertEquals("FINISHED", correct.json("$.stage"));
    String receiptId = correct.json("$.receiptId");
    String replayedId = expectOk(accuse(alpha, key, CORRECT_SUSPECT)).json("$.receiptId");
    assertEquals(receiptId, replayedId);
    expectError(accuse(alpha, UUID.randomUUID(), CORRECT_SUSPECT), 409, "Your team has already solved the case.");
    Response finished = expectOk(get("/team/final", alpha.token()));
    assertNotNull(finished.json("$.finish.finishedAt"));
    assertEquals(CORRECT_SUSPECT, finished.json("$.finish.suspectName"));
    assertEquals(2, (int) finished.json("$.accusationAttempts"));
    assertEquals(1, count("SELECT count(*) FROM team_final"));
  }

  @Test
  void aClosedFinalRejectsRecoveryAndAccusations() {
    collectClues(alpha, RoomCode.AI, RoomCode.PROBLEM_SOLVING, RoomCode.LINUX);
    setRound(5);
    openFinal();
    expectOk(post("/admin/final/close", admin, null));
    expectError(recover(alpha, UUID.randomUUID(), "eight bits"), 409, "The final is not open.");
    expectError(accuse(alpha, UUID.randomUUID(), CORRECT_SUSPECT), 409, "The final is not open.");
    expectError(post("/admin/final/open", admin, null), 409, "The final was already opened once.");
  }

  @Test
  void theFinalOpensOnlyWithCompleteContent() {
    db.update("UPDATE event_room SET recovery_answer = '' WHERE code = 'LINUX'");
    db.update("UPDATE event_content SET encrypted_message = ''");
    Response refused = post("/admin/final/open", admin, null);
    assertEquals(409, refused.status());
    assertTrue(refused.detail().startsWith("Complete the event content before opening the final: "));
    assertTrue(refused.detail().contains("encrypted message"));
    assertTrue(refused.detail().contains("Linux recovery challenge"));
    assertEquals(false, expectOk(get("/admin/board", admin)).json("$.state.finalOpen"));
  }

  @Test
  void contentIsReadOnlyOnceTheFinalOpens() {
    openFinal();
    String locked = "Event content is read-only after the final opens.";
    String roomContent = "{\"clueKey\":\"X\",\"recoveryPrompt\":\"P\",\"recoveryAnswer\":\"A\",\"version\":1}";
    String aiRoom = "/rooms/" + rooms.get(RoomCode.AI) + "/content";
    expectError(put(aiRoom, managers.get(RoomCode.AI), roomContent), 409, locked);
    expectError(put(aiRoom, admin, roomContent), 409, locked);
    expectError(put("/admin/content", admin, "{\"encryptedMessage\":\"X\",\"correctSuspectId\":null,\"version\":1}"), 409, locked);
    expectError(post("/admin/suspects", admin, "{\"name\":\"New\",\"description\":\"\"}"), 409, locked);
    long suspect = suspectId("Nadia Murad");
    expectError(put("/admin/suspects/" + suspect, admin, "{\"name\":\"Renamed\",\"description\":\"\"}"), 409, locked);
    expectError(delete("/admin/suspects/" + suspect, admin), 409, locked);
    assertEquals(true, expectOk(get("/admin/content", admin)).json("$.readOnly"));
    assertEquals(false, expectOk(get("/rooms/" + rooms.get(RoomCode.AI) + "/board", admin)).json("$.room.contentEditable"));
  }

  private String stage(TestTeam team) {
    return expectOk(get("/team/final", team.token())).json("$.stage");
  }

  private int count(String sql) {
    return db.queryForObject(sql, Integer.class);
  }
}
