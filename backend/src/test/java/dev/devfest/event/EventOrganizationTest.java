package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Admin setup: accounts and sessions, the four-team group limit, room managers, suspects and board alerts. */
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
    assertTrue(codes.containsAll(List.of("GROUP_SIZE", "MISSING_CLUE_KEY", "MISSING_RECOVERY", "MISSING_SUSPECTS", "MISSING_MESSAGE", "MISSING_CORRECT_SUSPECT")), codes.toString());
    loadContent();
    expectError(post("/admin/suspects", admin, "{\"name\":\"Seventh\",\"description\":\"\"}"), 409, "There are already 6 suspects.");
    expectError(delete("/admin/suspects/" + suspectId(CORRECT_SUSPECT), admin), 409, "Choose another correct suspect before deleting this one.");
    assertEquals(5, (int) expectOk(delete("/admin/suspects/" + suspectId("Kareem Shehab"), admin)).json("$.suspects.length()"));
    List<String> after = expectOk(get("/admin/board", admin)).json("$.alerts[*].code");
    assertEquals(List.of("GROUP_SIZE", "GROUP_SIZE", "GROUP_SIZE", "GROUP_SIZE", "MISSING_SUSPECTS"), after);
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
