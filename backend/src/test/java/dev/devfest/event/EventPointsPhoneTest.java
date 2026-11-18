package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The Hardware room (rules §21): the points ledger out of 100 (add, subtract, undo, clamp, idempotency), its x 0.15
 * score in the event ranking, the phone number answer that releases the room's existing key, and that every other
 * room behaves as before. The room runs the PHONE_POINTS activity, which the admin switches on per room.
 */
class EventPointsPhoneTest extends EventIntegrationTest {

  private static final String PHONE = "+972-2-555-0147";
  private static final String KEY = CLUE_KEYS.get(RoomCode.HARDWARE);
  private static final String NOT_FOUND = "Room points not found.";

  private TestTeam alpha;
  private TestTeam bravo;
  private long hardware;
  private String manager;

  @BeforeEach
  void prepare() {
    loadContent();
    alpha = team("Alpha", GroupCode.A);
    bravo = team("Bravo", GroupCode.A);
    hardware = rooms.get(RoomCode.HARDWARE);
    manager = managers.get(RoomCode.HARDWARE);
    expectOk(put("/admin/rooms/" + hardware + "/activity", admin, "{\"activity\":\"PHONE_POINTS\"}"));
  }

  // ---- who may do what ----------------------------------------------------------------------------

  @Test
  void onlyTheRoomsManagerAndTheAdminReachThePointsAndOthersGetA404() {
    String ai = managers.get(RoomCode.AI);
    String path = "/rooms/" + hardware + "/points/teams/" + alpha.id();
    for (String organizer : List.of(manager, admin)) {
      expectOk(add(path, organizer, 1));
      expectOk(undo(path, organizer));
      expectOk(get(path + "/history", organizer));
    }
    for (String other : List.of(ai, managers.get(RoomCode.LINUX), alpha.token())) {
      expectError(add(path, other, 5), 404, NOT_FOUND);
      expectError(undo(path, other), 404, NOT_FOUND);
      expectError(get(path + "/history", other), 404, NOT_FOUND);
      expectError(put("/rooms/" + hardware + "/phone-number", other, "{\"phoneNumber\":\"1\"}"), 404, "Room not found.");
    }
    assertEquals(401, call(HttpMethod.POST, path, null, "{\"delta\":1}", UUID.randomUUID().toString()).status());
    assertEquals(401, get(path + "/history", null).status());
    // A room without the activity, and an unknown room or team, look the same.
    long ai0 = rooms.get(RoomCode.AI);
    expectError(add("/rooms/" + ai0 + "/points/teams/" + alpha.id(), ai, 5), 404, NOT_FOUND);
    expectError(add("/rooms/" + Long.MAX_VALUE + "/points/teams/" + alpha.id(), admin, 5), 404, NOT_FOUND);
    expectError(add("/rooms/" + hardware + "/points/teams/" + Long.MAX_VALUE, manager, 5), 404, "Team not found.");
    expectOk(put("/admin/rooms/" + hardware + "/activity", admin, "{\"activity\":null}"));
    expectError(add(path, manager, 5), 404, NOT_FOUND);
    expectError(put("/rooms/" + hardware + "/phone-number", manager, "{\"phoneNumber\":\"1\"}"), 404, "Room not found.");
  }

  // ---- the ledger ---------------------------------------------------------------------------------

  @Test
  void addsSubtractsAndUndoesStepByStepAndTheRoomBoardShowsIt() {
    String path = "/rooms/" + hardware + "/points/teams/" + alpha.id();
    assertEquals(30, (int) expectOk(add(path, manager, 30)).json("$.total"));
    assertEquals(70, (int) expectOk(add(path, manager, 40)).json("$.total"));
    Response afterSubtract = expectOk(add(path, admin, -5));
    assertEquals(65, (int) afterSubtract.json("$.total"));
    assertEquals(9.75, ((Number) afterSubtract.json("$.score")).doubleValue());
    assertEquals(-5, (int) afterSubtract.json("$.undoDelta"), "the label says what undo will reverse");

    Response undone = expectOk(undo(path, manager));
    assertEquals(70, (int) undone.json("$.total"));
    assertEquals(10.5, ((Number) undone.json("$.score")).doubleValue());
    assertEquals(40, (int) undone.json("$.undoDelta"), "a second undo steps back to the +40; it never undoes the undo");
    assertEquals(30, (int) expectOk(undo(path, manager)).json("$.total"));
    assertEquals(0, (int) expectOk(undo(path, manager)).json("$.total"));
    expectError(undo(path, manager), 409, "There is nothing to undo.");
    expectError(undo("/rooms/" + hardware + "/points/teams/" + bravo.id(), manager), 409, "There is nothing to undo.");

    Response history = expectOk(get(path + "/history", manager));
    assertEquals(List.of(-30, -40, 5, -5, 40, 30), history.json("$[*].delta"), "newest first");
    assertEquals(List.of(0, 30, 70, 65, 70, 30), history.json("$[*].totalAfter"));
    assertEquals(List.of(false, false, false, true, true, true), history.json("$[*].undone"), "the undone rows are marked; undo rows never are");
    assertEquals(6, count("SELECT count(*) FROM room_points_entry"), "rows are only ever appended");

    // The manager's board shows the total, the score and what undo would reverse.
    expectOk(add(path, manager, 10));
    Response board = expectOk(get("/rooms/" + hardware + "/board", manager));
    assertEquals(List.of(10), board.json("$.teams[?(@.teamName == 'Alpha')].pointsTotal"));
    assertEquals(List.of(1.5), board.json("$.teams[?(@.teamName == 'Alpha')].pointsScore"));
    assertEquals(List.of(10), board.json("$.teams[?(@.teamName == 'Alpha')].undoDelta"));
    assertEquals(List.of(0), board.json("$.teams[?(@.teamName == 'Bravo')].pointsTotal"));
    assertEquals(false, board.json("$.room.manualScoring"), "the 0-15 input is gone for this room");
  }

  @Test
  void aChangeThatLeavesZeroToHundredIsRefusedAndNothingIsWritten() {
    String path = "/rooms/" + hardware + "/points/teams/" + alpha.id();
    expectOk(add(path, manager, 85));
    expectError(add(path, manager, 20), 400, "The total must stay between 0 and 100 (now 85).");
    expectError(add(path, manager, -90), 400, "The total must stay between 0 and 100 (now 85).");
    expectOk(add(path, manager, 15));
    expectError(add(path, manager, 1), 400, "The total must stay between 0 and 100 (now 100).");
    expectError(add(path, manager, 0), 400, "The change cannot be 0.");
    expectError(add(path, manager, 101), 400, "The change must be from -100 to 100.");
    expectError(add(path, manager, -101), 400, "The change must be from -100 to 100.");
    expectError(call(HttpMethod.POST, path, manager, "{\"delta\":2.5}", UUID.randomUUID().toString()), 400, "The change must be a whole number of points.");
    assertEquals(400, call(HttpMethod.POST, path, manager, "{}", UUID.randomUUID().toString()).status());
    assertEquals(400, call(HttpMethod.POST, path, manager, "{\"delta\":1}", null).status(), "the Idempotency-Key header is required");
    assertEquals(2, count("SELECT count(*) FROM room_points_entry"));
    assertEquals(List.of(85, 100), db.queryForList("SELECT total_after FROM room_points_entry ORDER BY id", Integer.class));
  }

  @Test
  void aRepeatedRequestWithTheSameKeyNeverCountsTwice() {
    String path = "/rooms/" + hardware + "/points/teams/" + alpha.id();
    UUID key = UUID.randomUUID();
    Response first = call(HttpMethod.POST, path, manager, "{\"delta\":25}", key.toString());
    Response second = call(HttpMethod.POST, path, manager, "{\"delta\":25}", key.toString());
    assertEquals(25, (int) expectOk(first).json("$.total"));
    assertEquals(25, (int) expectOk(second).json("$.total"), "the repeat answers with the same result");
    assertEquals(1, count("SELECT count(*) FROM room_points_entry"));
    long revision = revision();
    expectOk(call(HttpMethod.POST, path, manager, "{\"delta\":25}", key.toString()));
    assertEquals(revision, revision(), "a repeat changes nothing");
    expectError(call(HttpMethod.POST, path, manager, "{\"delta\":26}", key.toString()), 409, "This request key was already used for a different change.");
    expectError(call(HttpMethod.POST, "/rooms/" + hardware + "/points/teams/" + bravo.id(), manager, "{\"delta\":25}", key.toString()), 409, "This request key was already used for a different change.");

    UUID undoKey = UUID.randomUUID();
    expectOk(call(HttpMethod.POST, path + "/undo", manager, null, undoKey.toString()));
    expectOk(call(HttpMethod.POST, path + "/undo", manager, null, undoKey.toString()));
    assertEquals(2, count("SELECT count(*) FROM room_points_entry"), "a double click on Undo reverses once");
    assertEquals(0, count("SELECT COALESCE(SUM(delta), 0) FROM room_points_entry"));
  }

  @Test
  void eachAddAndUndoIsAuditedWithoutAnyOtherData() {
    String path = "/rooms/" + hardware + "/points/teams/" + alpha.id();
    expectOk(add(path, manager, 30));
    expectOk(undo(path, manager));
    assertEquals(
      List.of(
        "EVENT_POINTS_ADDED room:%d team:%d delta:30 total:30".formatted(hardware, alpha.id()),
        "EVENT_POINTS_UNDONE room:%d team:%d delta:-30 total:0".formatted(hardware, alpha.id())
      ),
      db.queryForList("SELECT action || ' ' || target FROM audit_event WHERE action LIKE 'EVENT_POINTS_%' ORDER BY id", String.class)
    );
  }

  // ---- the score in the ranking -------------------------------------------------------------------

  @Test
  void theRoomScoreIsTheTotalTimesZeroPointFifteenAndDeductionsApplyOnTop() {
    String path = "/rooms/" + hardware + "/points/teams/" + alpha.id();
    expectOk(add(path, manager, 37));
    assertEquals(new BigDecimal("5.55"), roomPart("Alpha", "base"));
    assertEquals("POINTS", roomSource("Alpha"));
    expectOk(add(path, manager, 63));
    assertEquals(new BigDecimal("15.00"), roomPart("Alpha", "base"));
    expectOk(add(path, manager, -20));
    assertEquals(new BigDecimal("12.00"), roomPart("Alpha", "score"));
    assignRound(roundFor(GroupCode.A, RoomCode.HARDWARE));
    expectOk(post("/rooms/" + hardware + "/hints", manager, "{\"teamId\":" + alpha.id() + "}"));
    assertEquals(new BigDecimal("10.00"), roomPart("Alpha", "score"), "a hint takes 2 off the x 0.15 score");
    assertEquals(new BigDecimal("12.00"), roomPart("Alpha", "base"));
    assertEquals(new BigDecimal("0.00"), roomPart("Bravo", "base"));
  }

  // ---- converting an old typed score --------------------------------------------------------------

  @Test
  void anOldTypedManualScoreBecomesOneLedgerEntryOfScoreDividedByZeroPointFifteen() throws Exception {
    expectOk(put("/admin/rooms/" + hardware + "/activity", admin, "{\"activity\":null}"));
    expectOk(put("/rooms/" + hardware + "/manual-scores/" + alpha.id(), manager, "{\"score\":12}"));
    expectOk(put("/rooms/" + hardware + "/manual-scores/" + bravo.id(), manager, "{\"score\":0}"));
    expectOk(put("/admin/rooms/" + hardware + "/activity", admin, "{\"activity\":\"PHONE_POINTS\"}"));
    assertEquals(0, count("SELECT count(*) FROM room_points_entry"));

    // Run the migration's own conversion statement.
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V25__room_points_phone.sql"), StandardCharsets.UTF_8);
    int start = sql.indexOf("INSERT INTO room_points_entry");
    String conversion = sql.substring(start, sql.indexOf(';', start));
    db.update(conversion);

    assertEquals(1, count("SELECT count(*) FROM room_points_entry"), "a score of 0 needs no entry");
    assertEquals(80, count("SELECT delta FROM room_points_entry WHERE team_id = " + alpha.id()));
    assertEquals(80, count("SELECT total_after FROM room_points_entry WHERE team_id = " + alpha.id()));
    assertEquals(new BigDecimal("12.00"), roomPart("Alpha", "base"), "the score is the same as before");
    assertEquals(1, count("SELECT count(*) FROM room_manual_score WHERE team_id = " + alpha.id()), "the old table is kept");
    Response board = expectOk(get("/rooms/" + hardware + "/board", manager));
    assertEquals(List.of(80), board.json("$.teams[?(@.teamName == 'Alpha')].pointsTotal"));
  }

  // ---- the phone number answer --------------------------------------------------------------------

  private TestTeam hardwareTeam() {
    setRound(roundFor(GroupCode.A, RoomCode.HARDWARE));
    return alpha;
  }

  @Test
  void theManagerSetsThePhoneNumberAndItIsAuditedWithoutItsValue() {
    expectOk(put("/rooms/" + hardware + "/phone-number", manager, "{\"phoneNumber\":\"" + PHONE + "\"}"));
    assertEquals(PHONE, db.queryForObject("SELECT phone_number FROM event_room WHERE id = ?", String.class, hardware));
    assertEquals(PHONE, expectOk(get("/rooms/" + hardware + "/board", manager)).json("$.room.phoneNumber"));
    assertEquals(true, expectOk(get("/rooms/" + hardware + "/board", manager)).json("$.room.phoneNumberSet"));
    assertEquals(
      List.of("room:" + hardware + " set:true"),
      db.queryForList("SELECT target FROM audit_event WHERE action = 'EVENT_PHONE_NUMBER_SET'", String.class)
    );
    assertFalse(db.queryForList("SELECT target || coalesce(reason, '') FROM audit_event", String.class).toString().contains("5550147"), "the number is never audited");
    expectOk(put("/rooms/" + hardware + "/phone-number", admin, "{\"phoneNumber\":\"\"}"));
    assertEquals(false, expectOk(get("/rooms/" + hardware + "/board", manager)).json("$.room.phoneNumberSet"));
    assertEquals(400, put("/rooms/" + hardware + "/phone-number", manager, "{\"phoneNumber\":\"" + "1".repeat(41) + "\"}").status());
    expectError(put("/rooms/" + hardware + "/phone-number", manager, "{\"phoneNumber\":\"call me\"}"), 400, "The phone number needs at least one digit.");
    assertEquals(400, put("/rooms/" + hardware + "/phone-number", manager, "{}").status());
    expectOk(put("/rooms/" + hardware + "/phone-number", manager, "{\"phoneNumber\":\"" + PHONE + "\"}"));
    // Room content: read-only once the final has opened. The room key stays untouched throughout.
    assertEquals(KEY, db.queryForObject("SELECT clue_key FROM event_room WHERE id = ?", String.class, hardware));
    openFinal();
    expectError(put("/rooms/" + hardware + "/phone-number", manager, "{\"phoneNumber\":\"1\"}"), 409, "Event content is read-only after the final opens.");
  }

  @Test
  void aCorrectNumberInAnyFormatCollectsTheRoomsExistingKeyAndAWrongOneDoesNot() {
    expectOk(put("/rooms/" + hardware + "/phone-number", manager, "{\"phoneNumber\":\"" + PHONE + "\"}"));
    TestTeam team = hardwareTeam();
    openRoom(RoomCode.HARDWARE);

    Response wrong = expectOk(submit(bravo, RoomCode.HARDWARE, UUID.randomUUID(), "ignored", "+972 2 555 0148"));
    assertEquals(false, wrong.json("$.clueCorrect"));
    assertEquals(0, count("SELECT count(*) FROM collected_clue WHERE team_id = " + bravo.id()));
    assertEquals(List.of(false), expectOk(get("/team/board", bravo.token())).json("$.evidence[?(@.roomCode == 'HARDWARE')].clueCollected"));

    UUID key = UUID.randomUUID();
    Response right = expectOk(submit(team, RoomCode.HARDWARE, key, "ignored", "972-2-5550147"));
    assertEquals(true, right.json("$.clueCorrect"));
    assertEquals(1, count("SELECT count(*) FROM collected_clue WHERE team_id = " + team.id() + " AND room_id = " + hardware + " AND source = 'ROOM'"));
    assertEquals(
      "Phone number answer",
      db.queryForObject("SELECT technical_output FROM room_submission WHERE team_id = ?", String.class, team.id()),
      "the team types only the number; the technical output is a fixed note"
    );

    // The team gets the room's EXISTING key, at once, as its collected clue.
    Response board = expectOk(get("/team/board", team.token()));
    assertEquals(List.of(KEY), board.json("$.evidence[?(@.roomCode == 'HARDWARE')].clueValue"));
    assertEquals(List.of("PHONE_POINTS"), board.json("$.evidence[?(@.roomCode == 'HARDWARE')].activity"));
    assertTrue(expectOk(get("/team/submissions", team.token())).body().contains("HARDWARE"), "an ordinary receipt");

    // The same request key answers with the same receipt and changes nothing; the final's unlock still uses this key.
    assertEquals(true, expectOk(submit(team, RoomCode.HARDWARE, key, "ignored", "972-2-5550147")).json("$.clueCorrect"));
    assertEquals(1, count("SELECT count(*) FROM room_submission WHERE team_id = " + team.id()));
  }

  @Test
  void aRoomWithoutAPhoneNumberIsNotReadyAndTheAdminBoardWarnsUntilItIsSet() {
    List<String> codes = expectOk(get("/admin/board", admin)).json("$.alerts[*].code");
    assertTrue(codes.contains("PHONE_NUMBER_MISSING"), codes.toString());
    assertEquals(
      List.of("/event/room/" + hardware),
      expectOk(get("/admin/board", admin)).json("$.alerts[?(@.code == 'PHONE_NUMBER_MISSING')].link")
    );
    hardwareTeam();
    openRoom(RoomCode.HARDWARE);
    expectError(submit(alpha, RoomCode.HARDWARE, UUID.randomUUID(), "x", "97059881"), 409, "This room is not ready yet. Ask the organizer.");
    expectOk(put("/rooms/" + hardware + "/phone-number", manager, "{\"phoneNumber\":\"" + PHONE + "\"}"));
    assertFalse(expectOk(get("/admin/board", admin)).<List<String>>json("$.alerts[*].code").contains("PHONE_NUMBER_MISSING"));
    expectOk(put("/admin/rooms/" + hardware + "/activity", admin, "{\"activity\":null}"));
    expectOk(put("/rooms/" + hardware + "/content", manager, "{\"clueKey\":\"%s\",\"version\":%d}".formatted(KEY, roomVersion(RoomCode.HARDWARE))));
    assertFalse(expectOk(get("/admin/board", admin)).<List<String>>json("$.alerts[*].code").contains("PHONE_NUMBER_MISSING"), "only a points-and-phone room is warned about");
  }

  @Test
  void theManagersOverrideStillAcceptsAndRejectsAndTheNumberIsNeverInAMemberResponse() {
    expectOk(put("/rooms/" + hardware + "/phone-number", manager, "{\"phoneNumber\":\"" + PHONE + "\"}"));
    TestTeam team = hardwareTeam();
    openRoom(RoomCode.HARDWARE);
    expectOk(submit(bravo, RoomCode.HARDWARE, UUID.randomUUID(), "x", "000"));
    String submission = db.queryForObject("SELECT id::text FROM room_submission WHERE team_id = ?", String.class, bravo.id());
    expectOk(put("/rooms/" + hardware + "/submissions/" + submission + "/clue-override", manager, "{\"override\":\"ACCEPT\",\"version\":1}"));
    assertEquals(1, count("SELECT count(*) FROM collected_clue WHERE team_id = " + bravo.id()), "the manager can still accept a wrong number");
    expectOk(put("/rooms/" + hardware + "/submissions/" + submission + "/clue-override", manager, "{\"override\":\"REJECT\",\"version\":2}"));
    assertEquals(0, count("SELECT count(*) FROM collected_clue WHERE team_id = " + bravo.id()));

    expectOk(submit(team, RoomCode.HARDWARE, UUID.randomUUID(), "x", "972255501xx47"));
    for (String path : List.of("/team/board", "/team/submissions", "/team/final", "/home", "/team/rooms/" + hardware + "/activity")) {
      for (TestTeam member : List.of(team, bravo)) {
        Response response = get(path, member.token());
        if (response.status() == 404 || response.status() == 409) continue;
        assertFalse(response.body().contains(PHONE), member.name() + " " + path + " leaked the phone number");
        assertFalse(response.body().contains("phoneNumber"), member.name() + " " + path + " carries a phone number field");
        assertFalse(response.body().contains("pointsTotal") || response.body().contains("undoDelta"), path + " leaked points");
      }
    }
  }

  // ---- other rooms are unchanged ------------------------------------------------------------------

  @Test
  void otherRoomsKeepTheirTypedScoreAndPlainAnswerForm() {
    long linux = rooms.get(RoomCode.LINUX);
    expectOk(put("/rooms/" + linux + "/manual-scores/" + alpha.id(), managers.get(RoomCode.LINUX), "{\"score\":9}"));
    assertEquals(new BigDecimal("9.00"), roomPart("Alpha", 2, "base"));
    assertEquals("MANUAL", roomSource("Alpha", 2));
    assertEquals(true, expectOk(get("/rooms/" + linux + "/board", managers.get(RoomCode.LINUX))).json("$.room.manualScoring"));
    // A plain room compares its clue key, takes the technical output as typed, and has no phone number.
    expectOk(put("/admin/rooms/" + hardware + "/activity", admin, "{\"activity\":null}"));
    TestTeam team = team("Delta", GroupCode.B);
    collectClues(team, RoomCode.LINUX);
    assertEquals("output", db.queryForObject("SELECT technical_output FROM room_submission WHERE team_id = ?", String.class, team.id()));
    // The Hardware room without the activity keeps the typed score and refuses nothing it did not refuse before.
    expectOk(put("/rooms/" + hardware + "/manual-scores/" + alpha.id(), managers.get(RoomCode.HARDWARE), "{\"score\":4}"));
    assertEquals("MANUAL", roomSource("Alpha", 3));
    expectError(put("/rooms/" + hardware + "/manual-scores/" + alpha.id(), managers.get(RoomCode.HARDWARE), "{\"score\":16}"), 400, "The score must be from 0 to 15.");
  }

  // ---- resets and snapshots -----------------------------------------------------------------------

  @Test
  void aTeamResetAndAnEventResetDeleteTheLedgerAfterASnapshotThatHoldsIt() {
    expectOk(add("/rooms/" + hardware + "/points/teams/" + alpha.id(), manager, 40));
    expectOk(add("/rooms/" + hardware + "/points/teams/" + bravo.id(), manager, 20));
    expectOk(undo("/rooms/" + hardware + "/points/teams/" + bravo.id(), manager));
    assertEquals(3, count("SELECT count(*) FROM room_points_entry"));
    String reason = "\"reason\":\"Points test\"";
    Response teamReset = expectOk(post("/admin/reset/team/" + alpha.id(), admin, "{" + reason + "}"));
    long snapshot = ((Number) teamReset.json("$.snapshotId")).longValue();
    assertEquals(1, snapshotRows(snapshot, "room_points_entry"));
    assertEquals(2, count("SELECT count(*) FROM room_points_entry"));
    assertEquals(0, count("SELECT count(*) FROM room_points_entry WHERE team_id = " + alpha.id()));
    String body = "{\"confirmText\":\"RESET\",\"password\":\"%s\",%s}".formatted(PASSWORD, reason);
    Response all = expectOk(post("/admin/reset/event", admin, body));
    long eventSnapshot = ((Number) all.json("$.snapshotId")).longValue();
    assertEquals(2, snapshotRows(eventSnapshot, "room_points_entry"));
    assertEquals(0, count("SELECT count(*) FROM room_points_entry"));
    assertEquals(KEY, db.queryForObject("SELECT clue_key FROM event_room WHERE id = ?", String.class, hardware), "the room's content stays");
    Response manual = expectOk(post("/admin/snapshots", admin, "{" + reason + "}"));
    assertEquals(0, snapshotRows(((Number) manual.json("$.snapshotId")).longValue(), "room_points_entry"));
  }

  // ---- helpers ------------------------------------------------------------------------------------

  private Response add(String path, String token, int delta) {
    return call(HttpMethod.POST, path, token, "{\"delta\":" + delta + "}", UUID.randomUUID().toString());
  }

  private Response undo(String path, String token) {
    return call(HttpMethod.POST, path + "/undo", token, null, UUID.randomUUID().toString());
  }

  private long count(String sql) {
    return db.queryForObject(sql, Long.class);
  }

  private int snapshotRows(long snapshot, String table) {
    return db.queryForObject("SELECT jsonb_array_length(data -> ?) FROM event_snapshot WHERE id = ?", Integer.class, table, snapshot);
  }

  private BigDecimal roomPart(String teamName, String part) {
    return roomPart(teamName, 3, part);
  }

  private BigDecimal roomPart(String teamName, int roomIndex, String part) {
    List<Number> found = expectOk(get("/admin/scores", admin)).json("$[?(@.teamName == '" + teamName + "')].rooms[" + roomIndex + "]." + part);
    return new BigDecimal(found.get(0).toString()).setScale(2);
  }

  private String roomSource(String teamName) {
    return roomSource(teamName, 3);
  }

  private String roomSource(String teamName, int roomIndex) {
    List<String> found = expectOk(get("/admin/scores", admin)).json("$[?(@.teamName == '" + teamName + "')].rooms[" + roomIndex + "].source");
    return found.get(0);
  }
}
