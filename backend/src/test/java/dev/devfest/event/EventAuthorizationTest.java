package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.ArrayList;
import java.util.List;
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
    "/admin/export.csv"
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
    String penalty = "{\"teamId\":%d,\"kind\":\"HINT\",\"note\":\"\"}".formatted(member.id());
    expectError(post("/rooms/" + linux + "/penalties", aiManager, penalty), 404, "Room not found.");
    String content = "{\"clueKey\":\"X\",\"recoveryPrompt\":\"P\",\"recoveryAnswer\":\"A\",\"version\":1}";
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

  @Test
  void memberResponsesNeverContainAnswerKeys() {
    List<String> bodies = new ArrayList<>();
    bodies.add(expectOk(get("/team/board", member.token())).body());
    bodies.add(expectOk(get("/team/final", member.token())).body());
    collectClues(member, RoomCode.AI, RoomCode.PROBLEM_SOLVING, RoomCode.LINUX);
    setRound(5);
    openFinal();
    bodies.add(expectOk(get("/team/board", member.token())).body());
    bodies.add(expectOk(get("/team/final", member.token())).body());
    bodies.add(expectOk(get("/team/submissions", member.token())).body());
    bodies.add(expectOk(recover(member, UUID.randomUUID(), RECOVERY_ANSWERS.get(RoomCode.HARDWARE))).body());
    bodies.add(expectOk(get("/team/final", member.token())).body());
    bodies.add(expectOk(accuse(member, UUID.randomUUID(), "Nadia Murad")).body());
    bodies.add(expectOk(accuse(member, UUID.randomUUID(), CORRECT_SUSPECT)).body());
    bodies.add(expectOk(get("/team/final", member.token())).body());
    expectOk(put("/admin/publication", admin, "{\"published\":true}"));
    bodies.add(expectOk(get("/results", member.token())).body());
    for (String body : bodies) assertNoSecrets(body);
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

  private static void assertNoSecrets(String body) {
    for (String key : CLUE_KEYS.values()) assertFalse(body.contains(key), "clue key leaked: " + body);
    for (String answer : RECOVERY_ANSWERS.values()) assertFalse(body.contains(answer), "recovery answer leaked: " + body);
    for (String field : List.of("clueKey", "recoveryAnswer", "correctSuspect")) {
      assertFalse(body.contains(field), field + " leaked: " + body);
    }
  }

  private static String judgement() {
    return "{\"status\":\"PASS\",\"hints\":0,\"note\":\"\",\"version\":0}";
  }
}
