package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The organizer dashboard of a Linux terminal room (rules §18.8): who may open it, the answer keys, the live scores
 * out of 15 computed on read, the overrides and their audit, the export, and that nothing here reaches teams or the
 * event's ranking. Charlie (group C) is in the Linux room in round 1, Bravo (B) in round 2, Alpha (A) in round 3.
 */
class EventTerminalDashboardTest extends EventIntegrationTest {

  private static final String CLUE = CLUE_KEYS.get(RoomCode.LINUX);
  private static final String REASON = "\"reason\":\"Dashboard test\"";
  private static final String NOT_FOUND = "Dashboard not found.";
  private static final String KEYS = "{\"path\":\"/srv/rami/uploads\\n/var/uploads\",\"username\":\"tech9\",\"fileSize\":\"1337c\",\"pattern\":\"*.bak\"}";

  @MockitoSpyBean
  private JdbcTemplate spiedJdbc;

  private long linux;
  private String manager;

  @BeforeEach
  void prepare() {
    loadContent();
    linux = rooms.get(RoomCode.LINUX);
    manager = managers.get(RoomCode.LINUX);
    expectOk(put("/admin/rooms/" + linux + "/activity", admin, "{\"activity\":\"LINUX_TERMINAL\"}"));
  }

  // ---- who may open it ------------------------------------------------------------------------------

  @Test
  void onlyTheRoomsManagerAndTheAdminReachTheDashboardEveryoneElseGetsA404() {
    TestTeam alpha = team("Alpha", GroupCode.A);
    String[] reads = { "/rooms/" + linux + "/terminal-dashboard", "/rooms/" + linux + "/terminal-dashboard.csv" };
    for (String read : reads) {
      expectOk(get(read, admin));
      expectOk(get(read, manager));
      expectError(get(read, managers.get(RoomCode.AI)), 404, NOT_FOUND);
      expectError(get(read, managers.get(RoomCode.HARDWARE)), 404, NOT_FOUND);
      expectError(get(read, alpha.token()), 404, NOT_FOUND);
      assertEquals(401, get(read, null).status());
    }
    String keys = "/rooms/" + linux + "/terminal-dashboard/keys";
    String item = "/rooms/" + linux + "/terminal-dashboard/teams/" + alpha.id() + "/items/PATH";
    expectOk(put(keys, manager, KEYS));
    expectOk(put(keys, admin, KEYS));
    expectError(put(keys, managers.get(RoomCode.AI), KEYS), 404, NOT_FOUND);
    expectError(put(keys, alpha.token(), KEYS), 404, NOT_FOUND);
    expectOk(put(item, manager, "{\"awarded\":true}"));
    expectError(put(item, managers.get(RoomCode.AI), "{\"awarded\":true}"), 404, NOT_FOUND);
    expectError(put(item, alpha.token(), "{\"awarded\":true}"), 404, NOT_FOUND);
    // An unknown room and a room without the activity look the same.
    expectError(get("/rooms/" + Long.MAX_VALUE + "/terminal-dashboard", admin), 404, NOT_FOUND);
    expectError(get("/rooms/" + rooms.get(RoomCode.AI) + "/terminal-dashboard", admin), 404, NOT_FOUND);
    expectError(get("/rooms/" + rooms.get(RoomCode.AI) + "/terminal-dashboard", managers.get(RoomCode.AI)), 404, NOT_FOUND);
    expectOk(put("/admin/rooms/" + linux + "/activity", admin, "{\"activity\":null}"));
    expectError(get(reads[0], manager), 404, NOT_FOUND);
  }

  // ---- the keys -------------------------------------------------------------------------------------

  @Test
  void theKeysAreSavedReturnedAndWarnedAboutWhileEmptyAndNeverAuditedAsContent() {
    Response empty = expectOk(get("/rooms/" + linux + "/terminal-dashboard", manager));
    assertEquals(List.of("PATH", "USERNAME", "FILE_SIZE", "PATTERN"), empty.json("$.emptyKeys"));
    assertEquals(15, (int) empty.json("$.maxPoints"));
    assertEquals(3, (int) empty.json("$.pointsPerItem"));
    List<String> warnings = expectOk(get("/admin/board", admin)).json("$.alerts[?(@.code == 'TERMINAL_KEYS_MISSING')].message");
    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).startsWith("Room Linux has no answer key for PATH, USERNAME, FILE_SIZE, PATTERN"), warnings.get(0));
    assertEquals(List.of("/event/room/" + linux + "/dashboard"), expectOk(get("/admin/board", admin)).json("$.alerts[?(@.code == 'TERMINAL_KEYS_MISSING')].link"));

    long before = revision();
    Response saved = expectOk(put("/rooms/" + linux + "/terminal-dashboard/keys", manager, KEYS));
    assertTrue(revision() > before, "the admin board's warning follows");
    assertEquals("/srv/rami/uploads\n/var/uploads", saved.json("$.keys.path"));
    assertEquals(List.of(), saved.json("$.emptyKeys"));
    assertEquals(List.of(), expectOk(get("/admin/board", admin)).json("$.alerts[?(@.code == 'TERMINAL_KEYS_MISSING')]"));

    expectOk(put("/rooms/" + linux + "/terminal-dashboard/keys", admin, keysJson("", "tech9", "", "")));
    assertEquals(
      List.of("PATH", "FILE_SIZE", "PATTERN"),
      expectOk(get("/rooms/" + linux + "/terminal-dashboard", manager)).json("$.emptyKeys"),
      "an empty key is allowed and warned about"
    );
    String audits = db.queryForList("SELECT action || ' ' || target || ' ' || COALESCE(reason, '') FROM audit_event WHERE action = 'EVENT_TERMINAL_KEYS_SAVED'", String.class).toString();
    assertTrue(audits.contains("EVENT_TERMINAL_KEYS_SAVED room:" + linux));
    assertFalse(audits.contains("tech9") || audits.contains("rami"), "answers never reach the audit log");
  }

  @Test
  void keysAreValidatedAndFollowTheContentLockWhileOverridesDoNot() {
    String keys = "/rooms/" + linux + "/terminal-dashboard/keys";
    expectError(put(keys, manager, keysJson("a\n".repeat(21), "", "", "")), 400, "PATH accepts at most 20 values.");
    assertEquals(400, put(keys, manager, "{\"path\":\"x\"}").status());
    expectOk(put(keys, manager, KEYS));
    TestTeam alpha = team("Alpha", GroupCode.A);
    openFinal();
    expectError(put(keys, manager, KEYS), 409, "Event content is read-only after the final opens.");
    assertEquals(false, expectOk(get("/rooms/" + linux + "/terminal-dashboard", manager)).json("$.keysEditable"));
    expectOk(put("/rooms/" + linux + "/terminal-dashboard/teams/" + alpha.id() + "/items/PATH", manager, "{\"awarded\":true}"));
  }

  // ---- scores ---------------------------------------------------------------------------------------

  private record Scenario(TestTeam charlie, TestTeam bravo, TestTeam alpha, TestTeam delta) {}

  /** Charlie scores 15, Bravo 6, Alpha has only saved (path right: 3), Delta never started. */
  private Scenario scenario() {
    expectOk(put("/rooms/" + linux + "/terminal-dashboard/keys", manager, KEYS));
    Scenario s = new Scenario(team("Charlie", GroupCode.C), team("Bravo", GroupCode.B), team("Alpha", GroupCode.A), team("Delta", GroupCode.D));
    setRound(1);
    openRoom(RoomCode.LINUX);
    expectOk(teamPost(s.charlie(), formPath() + "/submit", UUID.randomUUID(), form("/srv/rami/uploads/", "tech9", "1337C", "\"*.bak\"", " " + CLUE.toLowerCase())));
    setRound(2);
    expectOk(teamPost(s.bravo(), formPath() + "/submit", UUID.randomUUID(), form("/var/uploads", "nobody", "1337c", "*.log", "wrong")));
    setRound(3);
    expectOk(teamPost(s.alpha(), formPath() + "/save", UUID.randomUUID(), form("/srv/rami/uploads", "", "", "", "")));
    return s;
  }

  @Test
  void theTableShowsEveryActiveTeamWithItsStatusMarksAndTotalSortedByTotal() {
    scenario();
    Response view = expectOk(get("/rooms/" + linux + "/terminal-dashboard", manager));
    assertEquals(List.of("Charlie", "Bravo", "Alpha", "Delta"), view.json("$.teams[*].teamName"));
    assertEquals(List.of(15, 6, 3, 0), view.json("$.teams[*].total"));
    assertEquals(List.of("SUBMITTED", "SUBMITTED", "SAVED", "NOT_STARTED"), view.json("$.teams[*].status"));
    assertEquals(java.util.Arrays.asList("SUBMIT", "SUBMIT", null, null), view.json("$.teams[*].cause"));
    assertEquals(List.of(true, true, true, true, true), view.json("$.teams[0].items[*].awarded"));
    assertEquals(List.of(true, false, true, false, false), view.json("$.teams[1].items[*].awarded"), "path and size right, user, pattern and clue wrong");
    assertEquals(false, view.json("$.teams[1].clueCorrect"));
    assertEquals("/var/uploads", view.json("$.teams[1].path"), "the submitted values are shown");
    assertEquals(List.of("PATH", "USERNAME", "FILE_SIZE", "PATTERN", "CLUE"), view.json("$.teams[0].items[*].item"));
    assertEquals(List.of("PATH", "USERNAME", "FILE_SIZE", "PATTERN", "Clue"), view.json("$.teams[0].items[*].label"));
    assertFalse(view.body().contains("messageCorrect") || view.body().contains("encryptedMessage"), "the dashboard has no message field");
    assertNull(view.<Object>json("$.teams[3].submittedAt"));
    assertEquals(4, ((List<?>) view.json("$.teams[*]")).size());
  }

  @Test
  void scoresAreComputedOnReadSoANewKeyChangesThemWithoutAnyStoredTotal() {
    Scenario s = scenario();
    assertEquals(6, total(s.bravo()));
    // The organizer now accepts the other pattern too, and the manager fixes the clue key.
    expectOk(put("/rooms/" + linux + "/terminal-dashboard/keys", manager, keysJson("/srv/rami/uploads\\n/var/uploads", "tech9\\nnobody", "1337c", "*.bak\\n*.log")));
    assertEquals(12, total(s.bravo()), "username and pattern now count");
    expectOk(setClueKey(RoomCode.LINUX, "WRONG"));
    assertEquals(15, total(s.bravo()), "and the clue follows the current clue key");
    assertEquals(12, total(s.charlie()));
    assertEquals(
      0,
      (int) db.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name IN ('terminal_form', 'terminal_form_override', 'terminal_answer_key') AND (column_name LIKE '%total%' OR column_name LIKE '%score%')", Integer.class),
      "no total or score column exists"
    );
  }

  @Test
  void thereIsNoSeparateEncryptedMessageAnywhereInTheLinuxFormTablesWhileTheFinalKeepsItsOwn() {
    assertEquals(
      0,
      (int) db.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name IN ('terminal_form', 'terminal_answer_key', 'terminal_form_override') AND column_name LIKE '%message%'", Integer.class),
      "V17 dropped the message answer and the message key"
    );
    assertEquals(
      1,
      (int) db.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'event_content' AND column_name = 'encrypted_message'", Integer.class),
      "the final's encrypted message is untouched"
    );
    // The check constraint knows the new item and not the old one (an item the API would never send).
    assertEquals(
      1,
      (int) db.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = 'terminal_form_override_item_check' AND pg_get_constraintdef(oid) LIKE '%''CLUE''%' AND pg_get_constraintdef(oid) NOT LIKE '%MESSAGE_CLUE%'", Integer.class)
    );
  }

  @Test
  void anOverrideAwardsDeniesAndClearsOneItemIsAuditedAndLeavesTheCluesAlone() {
    Scenario s = scenario();
    String bravoPath = "/rooms/" + linux + "/terminal-dashboard/teams/" + s.bravo().id() + "/items/";
    int cluesBefore = db.queryForObject("SELECT count(*) FROM collected_clue", Integer.class);
    int submissionsBefore = db.queryForObject("SELECT count(*) FROM room_submission", Integer.class);

    Response awarded = expectOk(put(bravoPath + "USERNAME", manager, "{\"awarded\":true}"));
    assertEquals(9, total(awarded, s.bravo()));
    assertEquals(List.of(true), awarded.json("$.teams[?(@.teamName == 'Bravo')].items[1].override"));
    Response denied = expectOk(put(bravoPath + "PATH", admin, "{\"awarded\":false}"));
    assertEquals(6, total(denied, s.bravo()));
    assertEquals(List.of(false), denied.json("$.teams[?(@.teamName == 'Bravo')].items[0].awarded"));
    assertEquals(List.of(true), denied.json("$.teams[?(@.teamName == 'Bravo')].items[0].auto"), "the automatic result stays visible");

    // The fifth item (Clue): awarding it changes the points, never the room's clue. The old item name no longer exists.
    assertEquals(400, put(bravoPath + "MESSAGE_CLUE", manager, "{\"awarded\":true}").status());
    Response fifth = expectOk(put(bravoPath + "CLUE", manager, "{\"awarded\":true}"));
    assertEquals(9, total(fifth, s.bravo()));
    expectOk(put("/rooms/" + linux + "/terminal-dashboard/teams/" + s.charlie().id() + "/items/CLUE", manager, "{\"awarded\":false}"));
    assertEquals(12, total(s.charlie()));
    assertEquals(cluesBefore, (int) db.queryForObject("SELECT count(*) FROM collected_clue", Integer.class), "no clue was granted or revoked");
    assertEquals(submissionsBefore, (int) db.queryForObject("SELECT count(*) FROM room_submission", Integer.class));
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM collected_clue WHERE team_id = ?", Integer.class, s.bravo().id()));
    assertEquals(1, (int) db.queryForObject("SELECT count(*) FROM collected_clue WHERE team_id = ?", Integer.class, s.charlie().id()));

    // Clearing returns to automatic.
    expectOk(put(bravoPath + "USERNAME", manager, "{\"awarded\":null}"));
    expectOk(put(bravoPath + "PATH", manager, "{\"awarded\":null}"));
    expectOk(put(bravoPath + "CLUE", manager, "{}"));
    assertEquals(6, total(s.bravo()));
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM terminal_form_override WHERE team_id = ?", Integer.class, s.bravo().id()));

    List<String> audits = db.queryForList("SELECT target FROM audit_event WHERE action = 'EVENT_TERMINAL_SCORE_OVERRIDE' ORDER BY id", String.class);
    String bravo = "room:" + linux + " team:" + s.bravo().id();
    assertEquals(
      List.of(
        bravo + " item:USERNAME award",
        bravo + " item:PATH deny",
        bravo + " item:CLUE award",
        "room:" + linux + " team:" + s.charlie().id() + " item:CLUE deny",
        bravo + " item:USERNAME auto",
        bravo + " item:PATH auto",
        bravo + " item:CLUE auto"
      ),
      audits,
      "every set and every clear is audited"
    );
    assertFalse(audits.toString().contains("tech9"), "no answers in the audit");
  }

  @Test
  void anOverrideOnATeamThatNeverStartedStillCountsAndUnknownTeamsAndItemsAreRefused() {
    Scenario s = scenario();
    String base = "/rooms/" + linux + "/terminal-dashboard/teams/";
    assertEquals(3, total(expectOk(put(base + s.delta().id() + "/items/PATTERN", manager, "{\"awarded\":true}")), s.delta()));
    expectError(put(base + 987654 + "/items/PATH", manager, "{\"awarded\":true}"), 404, "Team not found.");
    assertEquals(400, put(base + s.delta().id() + "/items/NOPE", manager, "{\"awarded\":true}").status());
  }

  @Test
  void theFifteenPointsStayInTheRoomTheEventsRankingDoesNotChange() {
    Scenario s = scenario();
    String before = expectOk(get("/admin/board", admin)).json("$.ranking").toString();
    String resultsBefore = expectOk(get("/admin/board", admin)).json("$.ranking[*].clues").toString();
    expectOk(put("/rooms/" + linux + "/terminal-dashboard/teams/" + s.bravo().id() + "/items/PATH", manager, "{\"awarded\":true}"));
    expectOk(put("/rooms/" + linux + "/terminal-dashboard/keys", manager, keysJson("x", "x", "x", "x")));
    assertEquals(before, expectOk(get("/admin/board", admin)).json("$.ranking").toString());
    assertEquals(resultsBefore, expectOk(get("/admin/board", admin)).json("$.ranking[*].clues").toString());
    // And nothing here reaches a team.
    for (TestTeam team : List.of(s.charlie(), s.bravo())) {
      String board = expectOk(get("/team/board", team.token())).body();
      assertFalse(board.contains("tech9") || board.contains("terminal-dashboard") || board.contains("answerKey"), board);
    }
  }

  // ---- statements, export, reset ---------------------------------------------------------------------

  @Test
  void theDashboardUsesAConstantNumberOfStatementsWhateverTheNumberOfTeams() {
    expectOk(put("/rooms/" + linux + "/terminal-dashboard/keys", manager, KEYS));
    team("First", GroupCode.A);
    team("Second", GroupCode.C);
    List<String> few = viewStatements();
    teams(3);
    assertEquals(14, ((List<?>) expectOk(get("/rooms/" + linux + "/terminal-dashboard", manager)).json("$.teams")).size());
    List<String> many = viewStatements();
    assertEquals(few.size(), many.size(), "few: " + few + "\nmany: " + many);
    assertEquals(5, many.size(), many.toString());
  }

  @Test
  void theCsvExportNeutralizesFormulasAndListsPointsAndOverrides() {
    Scenario s = scenario();
    expectOk(put("/rooms/" + linux + "/terminal-dashboard/teams/" + s.bravo().id() + "/items/PATH", manager, "{\"awarded\":false}"));
    db.update("UPDATE event_team SET name = '=HYPERLINK(1)' WHERE id = ?", s.delta().id());
    Response csv = expectOk(get("/rooms/" + linux + "/terminal-dashboard.csv", manager));
    String[] lines = csv.body().split("\r\n");
    assertEquals(
      "team,group,status,submittedAt,path,username,fileSize,pattern,finalClue,pathPoints,usernamePoints,fileSizePoints,patternPoints,cluePoints,total,overridden",
      lines[0]
    );
    assertEquals(5, lines.length);
    assertTrue(lines[1].startsWith("\"Charlie\",C,SUBMITTED,"), lines[1]);
    assertTrue(lines[1].endsWith(",3,3,3,3,3,15,\"\""), lines[1]);
    assertTrue(lines[2].endsWith(",0,0,3,0,0,3,\"PATH\""), lines[2]);
    assertTrue(csv.body().contains("\"'=HYPERLINK(1)\""), "a team named like a formula is neutralized");
  }

  @Test
  void aTeamResetDeletesItsOverridesAfterASnapshotThatHoldsThemAndAnEventResetKeepsTheKeys() {
    Scenario s = scenario();
    expectOk(put("/rooms/" + linux + "/terminal-dashboard/teams/" + s.bravo().id() + "/items/PATH", manager, "{\"awarded\":false}"));
    Response reset = expectOk(post("/admin/reset/team/" + s.bravo().id(), admin, "{" + REASON + "}"));
    long snapshot = ((Number) reset.json("$.snapshotId")).longValue();
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM terminal_form_override", Integer.class));
    Response download = call(HttpMethod.GET, "/admin/snapshots/" + snapshot + "/download", admin, null, null);
    assertTrue(download.body().contains("terminal_form_override"), "the snapshot holds the overrides");
    assertEquals(0, total(s.bravo()));

    expectOk(put("/rooms/" + linux + "/terminal-dashboard/teams/" + s.charlie().id() + "/items/PATH", manager, "{\"awarded\":false}"));
    String body = "{\"confirmText\":\"RESET\",\"password\":\"%s\",%s}".formatted(PASSWORD, REASON);
    expectOk(post("/admin/reset/event", admin, body));
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM terminal_form_override", Integer.class));
    assertEquals(0, (int) db.queryForObject("SELECT count(*) FROM terminal_form", Integer.class));
    assertEquals("/srv/rami/uploads\n/var/uploads", expectOk(get("/rooms/" + linux + "/terminal-dashboard", manager)).json("$.keys.path"), "the keys are content: they stay");
  }

  // ---- helpers ------------------------------------------------------------------------------------

  private String formPath() {
    return "/team/rooms/" + linux + "/terminal-form";
  }

  private int total(TestTeam team) {
    return total(expectOk(get("/rooms/" + linux + "/terminal-dashboard", manager)), team);
  }

  private static int total(Response view, TestTeam team) {
    List<Integer> totals = view.json("$.teams[?(@.teamId == " + team.id() + ")].total");
    return totals.get(0);
  }

  private List<String> viewStatements() {
    expectOk(get("/rooms/" + linux + "/terminal-dashboard", manager));
    clearInvocations(spiedJdbc);
    expectOk(get("/rooms/" + linux + "/terminal-dashboard", manager));
    List<String> statements = new ArrayList<>();
    for (Invocation invocation : mockingDetails(spiedJdbc).getInvocations()) {
      Object[] args = invocation.getArguments();
      if (args.length == 0 || !(args[0] instanceof String sql) || sql.contains("auth_session")) continue;
      if (statements.isEmpty() || !statements.getLast().equals(sql)) statements.add(sql);
    }
    return statements;
  }

  private static String keysJson(String path, String username, String fileSize, String pattern) {
    return "{\"path\":\"%s\",\"username\":\"%s\",\"fileSize\":\"%s\",\"pattern\":\"%s\"}".formatted(path.replace("\n", "\\n"), username, fileSize, pattern);
  }

  private static String form(String path, String username, String fileSize, String pattern, String clue) {
    return "{\"path\":%s,\"username\":%s,\"fileSize\":%s,\"pattern\":%s,\"finalClue\":%s}".formatted(
      quote(path), quote(username), quote(fileSize), quote(pattern), quote(clue)
    );
  }
}
