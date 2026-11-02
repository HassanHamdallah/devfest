package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The in-browser terminal (rules §18): only the team assigned to a terminal room this round receives its files,
 * and only while the round is ASSIGNED, RUNNING or PAUSED. Charlie (group C) is in the Linux room in round 1,
 * Alpha (group A) in the AI room.
 */
class EventTerminalTest extends EventIntegrationTest {

  private static final String REASON = "\"reason\":\"Terminal test\"";
  private static final String LOCK_LOG = "10:01 door ok\n10:02 OVERRIDE east door\n";
  private static final int MB = 1024 * 1024;

  private TestTeam alpha;
  private TestTeam charlie;
  private long linux;

  @BeforeEach
  void prepare() {
    alpha = team("Alpha", GroupCode.A);
    charlie = team("Charlie", GroupCode.C);
    linux = rooms.get(RoomCode.LINUX);
  }

  @Test
  void theLinuxRoomHasTheTerminalByDefaultAndTheTeamBoardSaysSo() {
    assertEquals(true, db.queryForObject("SELECT terminal_enabled FROM event_room WHERE code = 'LINUX'", Boolean.class));
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM event_room WHERE terminal_enabled AND code <> 'LINUX'", Integer.class));
    assignRound(1);
    assertEquals(true, expectOk(get("/team/board", charlie.token())).json("$.scheduledRoom.terminalEnabled"));
    assertEquals(false, expectOk(get("/team/board", alpha.token())).json("$.scheduledRoom.terminalEnabled"));
    assertEquals(true, expectOk(get("/rooms/" + linux + "/board", managers.get(RoomCode.LINUX))).json("$.room.terminalEnabled"));
  }

  @Test
  void theAssignedTeamReceivesTheFilesWhileAssignedRunningOrPausedButNotAtTimeUp() {
    expectOk(addFile("logs/lock.log", LOCK_LOG));
    expectNotFound(teamFiles(charlie), "round 0");

    assignRound(1);
    Response assigned = expectOk(teamFiles(charlie));
    assertEquals("logs/lock.log", assigned.json("$.files[0].path"));
    assertEquals(LOCK_LOG, assigned.json("$.files[0].content"));
    expectNotFound(teamFiles(alpha), "another group in the same round");

    startTimer();
    expectOk(teamFiles(charlie));
    expectOk(post("/admin/round/timer/pause", admin, null));
    expectOk(teamFiles(charlie));
    expectOk(post("/admin/round/timer/resume", admin, null));
    expectOk(post("/admin/round/timer/end", admin, null));
    expectNotFound(teamFiles(charlie), "TIME_UP");

    assignRound(2);
    expectNotFound(teamFiles(charlie), "a later round in another room");
    assignRound(5);
    expectNotFound(teamFiles(charlie), "round 5");
  }

  @Test
  void aDisabledTerminalDisqualificationAndAnonymousCallersGetNothing() {
    expectOk(addFile("logs/lock.log", LOCK_LOG));
    setRound(1);
    expectOk(teamFiles(charlie));

    assertEquals(401, get("/team/rooms/" + linux + "/terminal-files", null).status());
    expectOk(post("/admin/teams/" + charlie.id() + "/disqualify", admin, "{" + REASON + "}"));
    expectNotFound(teamFiles(charlie), "disqualified");
    expectOk(post("/admin/teams/" + charlie.id() + "/reinstate", admin, "{" + REASON + "}"));
    expectOk(teamFiles(charlie));

    expectOk(setTerminal(linux, false));
    expectNotFound(teamFiles(charlie), "terminal disabled");
  }

  @Test
  void aRoomExceptionDecidesWhoReceivesTheFiles() {
    expectOk(addFile("logs/lock.log", LOCK_LOG));
    setRound(1);
    expectOk(put("/admin/overrides/room-exception", admin, exception(alpha, RoomCode.LINUX)));
    expectOk(put("/admin/overrides/room-exception", admin, exception(charlie, RoomCode.AI)));

    expectOk(teamFiles(alpha));
    expectNotFound(teamFiles(charlie), "moved out of the Linux room by the exception");
  }

  @Test
  void managersReachOnlyTheirOwnRoomsFilesAndTeamsNoManagerEndpoint() {
    expectOk(addFile("logs/lock.log", LOCK_LOG));
    String path = "/rooms/" + linux + "/terminal-files";
    assertEquals(LOCK_LOG, expectOk(get(path + "/content", managers.get(RoomCode.LINUX))).json("$.files[0].content"));
    expectOk(get(path + "/content", admin));
    expectError(get(path + "/content", managers.get(RoomCode.AI)), 404, "Room not found.");
    expectError(get(path, managers.get(RoomCode.AI)), 404, "Room not found.");
    expectError(get(path, charlie.token()), 403, "Access denied.");
    expectError(get("/team/rooms/" + linux + "/terminal-files", managers.get(RoomCode.LINUX)), 403, "A team account is required.");

    Response list = expectOk(get(path, managers.get(RoomCode.LINUX)));
    assertEquals(1, (int) list.json("$.files.length()"));
    assertEquals(LOCK_LOG.length(), (int) list.json("$.files[0].sizeBytes"));
    assertFalse(list.body().contains("OVERRIDE"), "the listing never carries content");
    long fileId = ((Number) list.json("$.files[0].id")).longValue();
    assertEquals(LOCK_LOG, expectOk(get(path + "/" + fileId, managers.get(RoomCode.LINUX))).json("$.content"));
    expectError(get(path + "/" + fileId, managers.get(RoomCode.AI)), 404, "Room not found.");
  }

  @Test
  void pathsAreValidated() {
    for (String bad : List.of("../x.log", "logs/../x.log", "/etc/passwd", "logs/a b.log", "logs//a.log", "./a.log", "logs/", "", "a".repeat(121), "logs/ü.log")) {
      Response response = addFile(bad, "x");
      assertEquals(400, response.status(), bad + " → " + response.body());
    }
    expectOk(addFile("a".repeat(120), "x"));
    expectOk(addFile("logs/sub-dir/Access_2024.v2.log", "x"));
    expectError(addFile("logs/sub-dir/Access_2024.v2.log", "y"), 409, "A file with this path already exists.");
  }

  @Test
  void sizeAndCountLimitsAreEnforced() {
    expectError(addFile("big.log", "x".repeat(2 * MB + 1)), 400, "The file is too large (max 2 MB).");
    expectError(addFile("nul.log", "a\u0000b"), 400, "Terminal files must be text.");
    expectOk(addFile("one.log", "x".repeat(2 * MB)));
    expectOk(addFile("two.log", "x".repeat(2 * MB)));
    expectError(addFile("three.log", "x".repeat(MB + 1)), 409, "The room's terminal files would exceed 5 MB in total.");
    expectOk(addFile("three.log", "x".repeat(MB)));

    // Replacing a file counts its new size instead of the old one.
    long one = fileId("one.log");
    expectOk(put("/rooms/" + linux + "/terminal-files/" + one, managers.get(RoomCode.LINUX), fileJson("one.log", "x".repeat(2 * MB))));
    expectError(
      put("/rooms/" + linux + "/terminal-files/" + one, managers.get(RoomCode.LINUX), fileJson("one.log", "x".repeat(2 * MB) + "é")),
      400,
      "The file is too large (max 2 MB)."
    );

    expectOk(delete("/rooms/" + linux + "/terminal-files/" + fileId("two.log"), managers.get(RoomCode.LINUX)));
    expectOk(delete("/rooms/" + linux + "/terminal-files/" + one, managers.get(RoomCode.LINUX)));
    expectOk(delete("/rooms/" + linux + "/terminal-files/" + fileId("three.log"), managers.get(RoomCode.LINUX)));
    for (int i = 1; i <= 20; i++) expectOk(addFile("logs/f" + i + ".log", "line " + i));
    expectError(addFile("logs/f21.log", "line 21"), 409, "A room can have at most 20 terminal files.");
  }

  @Test
  void changesAreAuditedWithoutContentAndBumpTheRevision() {
    long before = revision();
    Response list = expectOk(addFile("logs/lock.log", LOCK_LOG));
    assertTrue(revision() > before);
    long fileId = ((Number) list.json("$.files[0].id")).longValue();
    expectOk(put("/rooms/" + linux + "/terminal-files/" + fileId, admin, fileJson("logs/door.log", "OVERRIDE again\n")));
    expectOk(delete("/rooms/" + linux + "/terminal-files/" + fileId, managers.get(RoomCode.LINUX)));

    List<Map<String, Object>> audits = db.queryForList(
      "SELECT action, target FROM audit_event WHERE action LIKE 'EVENT_TERMINAL_FILE_%' ORDER BY id"
    );
    assertEquals(List.of("EVENT_TERMINAL_FILE_CREATED", "EVENT_TERMINAL_FILE_UPDATED", "EVENT_TERMINAL_FILE_DELETED"), audits.stream().map(a -> a.get("action")).toList());
    assertEquals("room:" + linux + " path:logs/lock.log bytes:" + LOCK_LOG.length(), audits.get(0).get("target"));
    assertEquals("room:" + linux + " path:logs/door.log bytes:15", audits.get(1).get("target"));
    String everything = db.queryForList("SELECT action || ' ' || target || ' ' || COALESCE(reason, '') FROM audit_event", String.class).toString();
    assertFalse(everything.contains("OVERRIDE"), "file content never reaches the audit log");
  }

  @Test
  void filesAreReadOnlyAfterTheFinalOpensAndNeedTheTerminalEnabled() {
    loadContent();
    Response list = expectOk(addFile("logs/lock.log", LOCK_LOG));
    long fileId = ((Number) list.json("$.files[0].id")).longValue();

    expectOk(setTerminal(linux, false));
    expectError(addFile("logs/other.log", "x"), 409, "The terminal is not enabled for this room.");
    expectOk(setTerminal(linux, true));

    openFinal();
    String readOnly = "Event content is read-only after the final opens.";
    expectError(addFile("logs/other.log", "x"), 409, readOnly);
    expectError(put("/rooms/" + linux + "/terminal-files/" + fileId, managers.get(RoomCode.LINUX), fileJson("logs/lock.log", "x")), 409, readOnly);
    expectError(delete("/rooms/" + linux + "/terminal-files/" + fileId, managers.get(RoomCode.LINUX)), 409, readOnly);
    assertEquals(false, expectOk(get("/rooms/" + linux + "/terminal-files", managers.get(RoomCode.LINUX))).json("$.editable"));
  }

  @Test
  void onlyTheAdminTogglesTheTerminalAndTheBoardWarnsAboutAnEmptyTerminal() {
    long ai = rooms.get(RoomCode.AI);
    expectError(setTerminal(ai, true, managers.get(RoomCode.AI)), 403, "Access denied.");
    assertEquals(List.of("Room Linux has the terminal enabled but no terminal files."), terminalAlerts());

    long before = revision();
    Response toggled = expectOk(setTerminal(ai, true));
    assertEquals(true, toggled.json("$.terminalEnabled"));
    assertTrue(revision() > before);
    long unchanged = revision();
    expectOk(setTerminal(ai, true));
    assertEquals(unchanged, revision(), "repeating the same setting is a no-op");
    assertEquals(true, expectOk(get("/admin/content", admin)).json("$.rooms[0].terminalEnabled"));
    assertEquals(2, terminalAlerts().size());

    expectOk(addFile("logs/lock.log", LOCK_LOG));
    expectOk(setTerminal(ai, false));
    assertEquals(List.of(), terminalAlerts());
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'EVENT_TERMINAL_DISABLED'", Integer.class));
  }

  // ---- helpers ------------------------------------------------------------------------------------

  private Response teamFiles(TestTeam team) {
    return get("/team/rooms/" + linux + "/terminal-files", team.token());
  }

  private static void expectNotFound(Response response, String situation) {
    assertEquals(404, response.status(), situation + ": " + response.body());
    assertEquals("Terminal files not found.", response.detail(), situation);
  }

  private Response addFile(String path, String content) {
    return post("/rooms/" + linux + "/terminal-files", managers.get(RoomCode.LINUX), fileJson(path, content));
  }

  private long fileId(String path) {
    return db.queryForObject("SELECT id FROM room_terminal_file WHERE room_id = ? AND path = ?", Long.class, linux, path);
  }

  private static String fileJson(String path, String content) {
    String json = quote(content).replace("\n", "\\n").replace("\u0000", "\\u0000");
    return "{\"path\":%s,\"content\":%s}".formatted(quote(path), json);
  }

  private Response setTerminal(long roomId, boolean enabled) {
    return setTerminal(roomId, enabled, admin);
  }

  private Response setTerminal(long roomId, boolean enabled, String token) {
    return put("/admin/rooms/" + roomId + "/terminal", token, "{\"enabled\":" + enabled + "}");
  }

  private String exception(TestTeam team, RoomCode room) {
    return "{\"teamId\":%d,\"roomId\":%d,%s}".formatted(team.id(), rooms.get(room), REASON);
  }

  private List<String> terminalAlerts() {
    Response board = expectOk(get("/admin/board", admin));
    return board.json("$.alerts[?(@.code == 'TERMINAL_WITHOUT_FILES')].message");
  }
}
