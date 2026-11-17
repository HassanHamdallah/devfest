package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** Role and ownership boundaries: 401 anonymous, 403 wrong role, 404 for another team's or room's resource. */
class EventAuthorizationTest extends EventIntegrationTest {

  private static final List<String> ADMIN_GETS = List.of(
    "/admin/board",
    "/admin/organization",
    "/admin/content",
    "/admin/export.csv",
    "/admin/scores",
    "/admin/deductions",
    "/admin/final/content",
    "/admin/final/timer"
  );

  private TestTeam member;
  private TestTeam otherMember;

  @BeforeEach
  void createTeams() {
    loadContent();
    member = team("Alpha", GroupCode.A);
    otherMember = team("Bravo", GroupCode.B);
  }

  @Test
  void memberCannotReachRoomBoardsOrAdminEndpoints() {
    for (RoomCode code : RoomCode.values()) {
      expectError(get("/rooms/" + rooms.get(code) + "/board", member.token()), 403, "Access denied.");
    }
    for (String path : ADMIN_GETS) assertEquals(403, get(path, member.token()).status(), path);
    assertEquals(403, post("/admin/round", member.token(), "{\"round\":1}").status());
    assertEquals(403, post("/admin/rooms/" + rooms.get(RoomCode.AI) + "/open", member.token(), null).status());
    assertEquals(403, post("/admin/final/open", member.token(), null).status());
    assertEquals(403, put("/admin/publication", member.token(), "{\"published\":true}").status());
    assertEquals(403, put("/rooms/" + rooms.get(RoomCode.AI) + "/judgements/" + member.id(), member.token(), judgement()).status());
  }

  @Test
  void memberCannotReadAnotherTeamsReceipt() {
    setRound(1);
    openRoom(RoomCode.PROBLEM_SOLVING);
    String otherReceipt = expectOk(submit(otherMember, RoomCode.PROBLEM_SOLVING, UUID.randomUUID(), "out", "x")).json("$.id");
    expectError(get("/team/submissions/" + otherReceipt, member.token()), 404, "Submission not found.");
    expectOk(get("/team/submissions/" + otherReceipt, otherMember.token()));
    assertEquals(0, (int) expectOk(get("/team/submissions", member.token())).json("$.length()"));
  }

  @Test
  void managerCannotTouchAnotherRoom() {
    String aiManager = managers.get(RoomCode.AI);
    long linux = rooms.get(RoomCode.LINUX);
    expectError(get("/rooms/" + linux + "/board", aiManager), 404, "Room not found.");
    expectError(put("/rooms/" + linux + "/judgements/" + member.id(), aiManager, judgement()), 404, "Room not found.");
    String hint = "{\"teamId\":%d,\"note\":\"\"}".formatted(member.id());
    expectError(post("/rooms/" + linux + "/hints", aiManager, hint), 404, "Room not found.");
    String cheating = "{\"teamId\":%d,\"reason\":\"Copied the neighbour\",\"confirmed\":true}".formatted(member.id());
    expectError(post("/rooms/" + linux + "/cheating", aiManager, cheating), 404, "Room not found.");
    expectError(get("/rooms/" + linux + "/deductions", aiManager), 404, "Room not found.");
    String content = "{\"clueKey\":\"X\",\"version\":1}";
    expectError(put("/rooms/" + linux + "/content", aiManager, content), 404, "Room not found.");
    expectOk(get("/rooms/" + rooms.get(RoomCode.AI) + "/board", aiManager));
    assertEquals(403, get("/admin/board", aiManager).status());
    assertEquals(403, get("/team/board", aiManager).status());
  }

  @Test
  void anonymousAndParticipantAreRejected() {
    assertEquals(401, get("/team/board", null).status());
    assertEquals(401, get("/revision", null).status());
    assertEquals(401, get("/admin/board", null).status());
    String participant = login(account("PARTICIPANT"));
    assertEquals(403, get("/revision", participant).status());
    assertEquals(403, get("/team/board", participant).status());
    expectOk(get("/api/v1/health", null));
  }

  /**
   * D3: a member receives a room's clue key only for a finished room whose clue its own team collected; never a
   * recovery answer, a passphrase or its hash, the correct suspect or another team's key. (collectClues answers with the key itself, so the
   * key texts of the collected rooms are the team's own answers too.)
   */
  @Test
  void memberResponsesContainOnlyTheKeysTheTeamCollected() {
    List<String> before = new ArrayList<>();
    before.add(expectOk(get("/team/board", member.token())).body());
    before.add(expectOk(get("/team/final", member.token())).body());
    for (String body : before) assertNoSecrets(body, Set.of());

    collectClues(member, RoomCode.AI, RoomCode.PROBLEM_SOLVING, RoomCode.LINUX);
    setRound(5);
    openFinal();
    Set<RoomCode> collected = Set.of(RoomCode.AI, RoomCode.PROBLEM_SOLVING, RoomCode.LINUX);
    List<String> beforeRecovery = new ArrayList<>();
    beforeRecovery.add(expectOk(get("/team/board", member.token())).body());
    beforeRecovery.add(expectOk(get("/team/final", member.token())).body());
    beforeRecovery.add(expectOk(get("/team/submissions", member.token())).body());
    for (String body : beforeRecovery) assertNoSecrets(body, collected);
    assertEquals(
      List.of(CLUE_KEYS.get(RoomCode.AI)),
      expectOk(get("/team/final", member.token())).json("$.evidence[?(@.roomCode == 'AI')].clueValue")
    );

    List<String> after = new ArrayList<>();
    after.add(expectOk(recover(member, UUID.randomUUID(), RECOVERY_ANSWER)).body());
    after.add(expectOk(get("/team/final", member.token())).body());
    after.add(expectOk(unlockWithKeys(member)).body());
    after.add(expectOk(get("/team/final", member.token())).body());
    after.add(expectOk(startCase(member)).body());
    for (int n = 1; n <= 4; n++) {
      after.add(expectOk(passphrase(member, n, PASSPHRASES.get(n - 1))).body());
      after.add(expectOk(get("/team/final", member.token())).body());
    }
    openInvestigationPhase();
    after.add(expectOk(suspect(member, WRONG_SUSPECT)).body());
    after.add(expectOk(get("/team/final", member.token())).body());
    after.add(expectOk(get("/team/board", member.token())).body());
    expectOk(put("/admin/publication", admin, "{\"published\":true}"));
    after.add(expectOk(get("/results", member.token())).body());
    for (String body : after) assertNoSecrets(body, EnumSet.allOf(RoomCode.class));

    // The other team collected nothing: none of Alpha's keys or answers reach it.
    for (String path : List.of("/team/board", "/team/final", "/team/submissions")) {
      assertNoSecrets(expectOk(get(path, otherMember.token())).body(), Set.of());
    }
  }

  @Test
  void unknownRoutesMethodsAndHeadersAreProblemDetails() {
    expectError(get("/no-such-endpoint", member.token()), 404, "Not found.");
    expectError(call(HttpMethod.DELETE, "/team/board", member.token(), null, null), 405, "Method not allowed.");
    Response missingKey = post(
      "/team/rooms/" + rooms.get(RoomCode.AI) + "/submissions",
      member.token(),
      "{\"technicalOutput\":\"x\",\"clueAnswer\":\"y\"}"
    );
    expectError(missingKey, 400, "Missing required header: Idempotency-Key");
    Response invalidKey = call(
      HttpMethod.POST,
      "/team/rooms/" + rooms.get(RoomCode.AI) + "/submissions",
      member.token(),
      "{\"technicalOutput\":\"x\",\"clueAnswer\":\"y\"}",
      "not-a-uuid"
    );
    expectError(invalidKey, 400, "Invalid request.");
  }

  /** {@code allowedKeys}: rooms whose clue key the caller may receive (D3). */
  private static void assertNoSecrets(String body, Set<RoomCode> allowedKeys) {
    CLUE_KEYS.forEach((room, key) -> {
      if (!allowedKeys.contains(room)) assertFalse(body.contains(key), room + " clue key leaked: " + body);
    });
    assertFalse(body.contains(RECOVERY_ANSWER), "recovery answer leaked: " + body);
    for (String field : List.of("clueKey", "recoveryAnswer", "correctSuspect", "acceptedAnswers", "passphraseSha256", "suspectCorrect")) {
      assertFalse(body.contains(field), field + " leaked: " + body);
    }
  }

  private static String judgement() {
    return "{\"status\":\"PASS\",\"note\":\"\",\"version\":0}";
  }
}
