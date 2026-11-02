package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Admin setup: accounts and sessions, the planned team count and its group cap, generated teams, the group
 * lock after round 1, room managers, suspects and board alerts.
 */
class EventOrganizationTest extends EventIntegrationTest {

  @Test
  void aGroupHoldsAtMostFourActiveTeams() {
    for (int i = 1; i <= 4; i++) createTeam("Team " + i, "A", createMember("member" + i));
    long fifthAccount = createMember("member5");
    expectError(
      post("/admin/teams", admin, teamBody("Team 5", "A", fifthAccount)),
      409,
      "Group A already has 4 active teams."
    );
    long first = db.queryForObject("SELECT id FROM event_team WHERE name = 'Team 1'", Long.class);
    long firstAccount = db.queryForObject("SELECT account_id FROM event_team WHERE id = ?", Long.class, first);
    String deactivate = "{\"name\":\"Team 1\",\"groupCode\":\"A\",\"accountId\":%d,\"active\":false}".formatted(firstAccount);
    expectOk(put("/admin/teams/" + first, admin, deactivate));
    expectOk(post("/admin/teams", admin, teamBody("Team 5", "A", fifthAccount)));
    Response organization = expectOk(get("/admin/organization", admin));
    assertEquals(4, (int) organization.json("$.groups[0].activeTeams"));
    assertEquals(0, (int) organization.json("$.groups[3].activeTeams"));
  }

  @Test
  void aMemberAccountRepresentsOnlyOneTeam() {
    long account = createMember("solo");
    createTeam("First", "B", account);
    expectError(post("/admin/teams", admin, teamBody("Second", "B", account)), 409, "That account is not an available member account.");
    long managerAccount = managerIds.get(RoomCode.AI);
    expectError(post("/admin/teams", admin, teamBody("Third", "B", managerAccount)), 409, "That account is not an available member account.");
  }

  @Test
  void deactivationAndPasswordResetRevokeSessions() {
    TestTeam alpha = team("Alpha", GroupCode.A);
    expectOk(get("/team/board", alpha.token()));
    expectOk(put("/admin/accounts/" + alpha.accountId() + "/active", admin, "{\"active\":false}"));
    assertEquals(401, get("/team/board", alpha.token()).status());
    expectOk(put("/admin/accounts/" + alpha.accountId() + "/active", admin, "{\"active\":true}"));
    String freshToken = login(db.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, alpha.accountId()));
    expectOk(get("/team/board", freshToken));
    expectOk(post("/admin/accounts/" + alpha.accountId() + "/password", admin, "{\"password\":\"Another-password-123\"}"));
    assertEquals(401, get("/team/board", freshToken).status());
    String adminAccount = "/admin/accounts/" + db.queryForObject("SELECT id FROM app_user WHERE role = 'ADMIN'", Long.class);
    expectError(put(adminAccount + "/active", admin, "{\"active\":false}"), 404, "Account not found.");
  }

  @Test
  void accountsAreCreatedWithUniqueEmails() {
    String body = "{\"email\":\"Manager.New@Event.test\",\"name\":\"New manager\",\"password\":\"Strong-password-123\",\"role\":\"MANAGER\"}";
    Response created = expectOk(post("/admin/accounts", admin, body));
    assertEquals("manager.new@event.test", created.json("$.email"));
    expectError(post("/admin/accounts", admin, body), 409, "An account with this email already exists.");
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'EVENT_ACCOUNT_CREATED'", Integer.class));
    assertTrue(db.queryForObject("SELECT target FROM audit_event WHERE action = 'EVENT_ACCOUNT_CREATED'", String.class).startsWith("account:"));
  }

  @Test
  void aManagerManagesOneRoom() {
    String body = "{\"managerId\":" + managerIds.get(RoomCode.AI) + "}";
    expectError(put("/admin/rooms/" + rooms.get(RoomCode.LINUX) + "/manager", admin, body), 409, "That manager already manages another room.");
    expectOk(put("/admin/rooms/" + rooms.get(RoomCode.LINUX) + "/manager", admin, "{\"managerId\":null}"));
    Response board = expectOk(get("/admin/board", admin));
    List<String> codes = board.json("$.alerts[*].code");
    assertTrue(codes.contains("ROOM_WITHOUT_MANAGER"), codes.toString());
  }

  @Test
  void suspectsAreLimitedToSixAndAlertsListMissingSetup() {
    Response board = expectOk(get("/admin/board", admin));
    List<String> codes = board.json("$.alerts[*].code");
    assertTrue(codes.containsAll(List.of("TEAM_COUNT", "MISSING_CLUE_KEY", "MISSING_RECOVERY", "MISSING_SUSPECTS", "MISSING_MESSAGE", "MISSING_CORRECT_SUSPECT")), codes.toString());
    loadContent();
    expectError(post("/admin/suspects", admin, "{\"name\":\"Seventh\",\"description\":\"\"}"), 409, "There are already 6 suspects.");
    expectError(delete("/admin/suspects/" + suspectId(CORRECT_SUSPECT), admin), 409, "Choose another correct suspect before deleting this one.");
    assertEquals(5, (int) expectOk(delete("/admin/suspects/" + suspectId("Kareem Shehab"), admin)).json("$.suspects.length()"));
    List<String> after = expectOk(get("/admin/board", admin)).json("$.alerts[*].code");
    // The Linux room has the terminal on by default and no terminal files yet (rules §18).
    assertEquals(List.of("TERMINAL_WITHOUT_FILES", "TEAM_COUNT", "MISSING_SUSPECTS"), after);
  }

  @Test
  void plannedTeamCountSetsTheGroupCap() {
    Response twenty = expectOk(put("/admin/teams/planned-count", admin, "{\"plannedCount\":20}"));
    assertEquals(20, (int) twenty.json("$.plannedTeamCount"));
    assertEquals(5, (int) twenty.json("$.maxPerGroup"));
    for (int i = 1; i <= 5; i++) team("A" + i, GroupCode.A);
    expectError(
      post("/admin/teams", admin, teamBody("A6", "A", createMember("extra6"))),
      409,
      "Group A already has 5 active teams."
    );
    expectError(
      put("/admin/teams/planned-count", admin, "{\"plannedCount\":16}"),
      409,
      "Group A has 5 active teams; 16 planned teams allow at most 4 per group."
    );
    assertEquals(400, put("/admin/teams/planned-count", admin, "{\"plannedCount\":65}").status());
    assertEquals(20, (int) db.queryForObject("SELECT planned_team_count FROM event_state WHERE id = 1", Integer.class));
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'EVENT_PLANNED_TEAMS'", Integer.class));
    List<String> warnings = expectOk(get("/admin/organization", admin)).json("$.warnings[*].code");
    assertEquals(List.of("TEAM_COUNT", "GROUP_UNBALANCED"), warnings);
    List<String> alerts = expectOk(get("/admin/board", admin)).json("$.alerts[*].code");
    assertTrue(alerts.containsAll(warnings), alerts.toString());
  }

  @Test
  void singleTeamCreationRespectsThePlannedCount() {
    expectOk(put("/admin/teams/planned-count", admin, "{\"plannedCount\":2}"));
    team("A1", GroupCode.A);
    team("B1", GroupCode.B);
    expectError(
      post("/admin/teams", admin, teamBody("C1", "C", createMember("extra3"))),
      409,
      "This would exceed the planned team count (2)."
    );
  }

  @Test
  void generatedTeamsFillTheSmallestGroupsAndReturnPasswordsOnce() {
    expectOk(put("/admin/teams/planned-count", admin, "{\"plannedCount\":8}"));
    team("Team 02", GroupCode.A);
    team("B1", GroupCode.B);
    String takenEmail = "team03@event.local";
    db.update("INSERT INTO app_user(email, name, password_hash, role) VALUES (?, 'Taken', 'x', 'MEMBER')", takenEmail);
    long revision = revision();

    Response generated = expectOk(post("/admin/teams/generate", admin, "{\"count\":6,\"prefix\":\"Team\"}"));

    assertEquals(List.of("Team 01", "Team 04", "Team 05", "Team 06", "Team 07", "Team 08"), generated.json("$[*].teamName"));
    assertEquals(List.of("C", "D", "A", "B", "C", "D"), generated.json("$[*].group"));
    assertEquals("team01@event.local", generated.json("$[0].email"));
    List<String> plain = generated.json("$[*].password");
    for (String password : plain) assertTrue(password.matches("[A-Za-z0-9]{16}"), password);
    assertEquals(revision + 1, revision());
    Response org = expectOk(get("/admin/organization", admin));
    assertEquals(List.of(2, 2, 2, 2), org.json("$.groups[*].activeTeams"));
    List<String> hashes = db.queryForList("SELECT password_hash FROM app_user WHERE email LIKE '%@event.local' AND email <> ?", String.class, takenEmail);
    assertEquals(6, hashes.size());
    for (String hash : hashes) assertTrue(hash.startsWith("$2"), "BCrypt hash expected");
    String stored = db.queryForObject(
      "SELECT string_agg(concat_ws('|', email, name, password_hash), ',') FROM app_user", String.class
    );
    String audited = db.queryForObject("SELECT string_agg(concat_ws('|', action, target), ',') FROM audit_event", String.class);
    for (String password : plain) {
      assertFalse(stored.contains(password), "plain password stored");
      assertFalse(audited.contains(password), "plain password audited");
    }
    assertEquals("count:6", db.queryForObject("SELECT target FROM audit_event WHERE action = 'EVENT_TEAMS_GENERATED'", String.class));
    String firstName = generated.json("$[0].teamName");
    assertEquals(firstName, auth.login("team01@event.local", plain.getFirst()).user().name());

    long accountsBefore = db.queryForObject("SELECT count(*) FROM app_user", Long.class);
    expectError(
      post("/admin/teams/generate", admin, "{\"count\":1,\"prefix\":\"Extra\"}"),
      409,
      "This would exceed the planned team count (8)."
    );
    assertEquals(accountsBefore, (long) db.queryForObject("SELECT count(*) FROM app_user", Long.class));
    assertEquals(8L, (long) db.queryForObject("SELECT count(*) FROM event_team", Long.class));
    assertEquals(400, post("/admin/teams/generate", admin, "{\"count\":1,\"prefix\":\"Bad-prefix\"}").status());
  }

  @Test
  void groupsAreLockedOnceRoundOneStarts() {
    TestTeam alpha = team("Alpha", GroupCode.A);
    String toB = "{\"name\":\"Alpha\",\"groupCode\":\"B\",\"accountId\":%d,\"active\":true}".formatted(alpha.accountId());
    assertEquals("B", expectOk(put("/admin/teams/" + alpha.id(), admin, toB)).json("$.groupCode"));
    setRound(1);
    String toC = "{\"name\":\"Alpha\",\"groupCode\":\"C\",\"accountId\":%d,\"active\":true}".formatted(alpha.accountId());
    expectError(put("/admin/teams/" + alpha.id(), admin, toC), 409, "Groups are locked after round 1 starts.");
    String rename = "{\"name\":\"Alpha One\",\"groupCode\":\"B\",\"accountId\":%d,\"active\":true}".formatted(alpha.accountId());
    expectOk(put("/admin/teams/" + alpha.id(), admin, rename));
    assertEquals(true, expectOk(get("/admin/organization", admin)).json("$.groupsLocked"));
    expectOk(post("/admin/teams/generate", admin, "{\"count\":1,\"prefix\":\"Late\"}"));
  }

  @Test
  void deactivatedTeamsLeaveRankingAndRoomListsButKeepTheirRecords() {
    loadContent();
    TestTeam kept = team("A1", GroupCode.A);
    TestTeam dropped = team("A2", GroupCode.A);
    collectClues(dropped, RoomCode.AI);
    String deactivate = "{\"name\":\"A2\",\"groupCode\":\"A\",\"accountId\":%d,\"active\":false}".formatted(dropped.accountId());
    expectOk(put("/admin/teams/" + dropped.id(), admin, deactivate));

    expectSessionEnded(get("/team/board", dropped.token()), "DEACTIVATED");
    String droppedEmail = db.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, dropped.accountId());
    assertEquals(401, call(org.springframework.http.HttpMethod.POST, "/api/v1/auth/login", null,
      "{\"email\":\"" + droppedEmail + "\",\"password\":\"" + PASSWORD + "\"}", null).status());
    Response board = expectOk(get("/admin/board", admin));
    assertEquals(List.of("A1"), board.json("$.ranking[*].teamName"));
    assertEquals(List.of("A1"), board.json("$.rooms[0].teamsNow"));
    List<String> roomTeams = expectOk(get("/rooms/" + rooms.get(RoomCode.AI) + "/board", managers.get(RoomCode.AI))).json("$.teams[*].teamName");
    assertEquals(List.of("A1"), roomTeams);
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM collected_clue WHERE team_id = ?", Integer.class, dropped.id()));
    expectOk(get("/team/board", kept.token()));
  }

  private long createMember(String name) {
    String body = "{\"email\":\"%s@event.test\",\"name\":\"%s\",\"password\":\"Strong-password-123\",\"role\":\"MEMBER\"}";
    return ((Number) expectOk(post("/admin/accounts", admin, body.formatted(name, name))).json("$.id")).longValue();
  }

  private void createTeam(String name, String group, long accountId) {
    expectOk(post("/admin/teams", admin, teamBody(name, group, accountId)));
  }

  private static String teamBody(String name, String group, long accountId) {
    return "{\"name\":\"%s\",\"groupCode\":\"%s\",\"accountId\":%d}".formatted(name, group, accountId);
  }
}
