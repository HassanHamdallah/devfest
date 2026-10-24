package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.jayway.jsonpath.JsonPath;
import dev.devfest.auth.AuthService;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared fixture for event integration tests on the disposable devfest_test database: a fresh event with
 * an admin, one manager per room and helpers that drive the real HTTP API with real JWTs.
 */
@SpringBootTest(
  properties = {
    "app.worker-enabled=false",
    "app.seed=false",
    "app.bootstrap-email=",
    "app.event-seed-password=",
    "app.jwt-secret=devfest-test-only-secret-with-48-characters-123456",
    "spring.config.import=",
  }
)
@AutoConfigureMockMvc
public abstract class EventIntegrationTest {

  protected static final String PASSWORD = "Event-test-only-password-1!";
  /** Cost 4 keeps logins fast; BCrypt reads the cost from the stored hash. */
  private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);

  protected static final Map<RoomCode, String> CLUE_KEYS = Map.of(
    RoomCode.AI, "WHITE VAN",
    RoomCode.PROBLEM_SOLVING, "RED CLAY",
    RoomCode.LINUX, "WEST TOWER",
    RoomCode.HARDWARE, "+970-59-881"
  );
  protected static final Map<RoomCode, String> RECOVERY_ANSWERS = Map.of(
    RoomCode.AI, "FOUR",
    RoomCode.PROBLEM_SOLVING, "THIRTY-TWO",
    RoomCode.LINUX, "PWD-SECRET",
    RoomCode.HARDWARE, "EIGHT BITS"
  );
  protected static final List<String> SUSPECTS = List.of(
    "Tariq Al-Khatib",
    "Nadia Murad",
    "Farid Mansour",
    "Leila Nasser",
    "Mazen Haddad",
    "Kareem Shehab"
  );
  protected static final String CORRECT_SUSPECT = "Leila Nasser";

  /** A test team with its member account's access token. */
  protected record TestTeam(long id, String name, GroupCode group, long accountId, String token) {}

  /** An HTTP response: status and raw body. */
  protected record Response(int status, String body) {
    public <T> T json(String path) {
      return JsonPath.read(body, path);
    }

    public String detail() {
      return json("$.detail");
    }

    /** Why a session ended, on a 401 for a revoked or expired session. */
    public String reason() {
      return json("$.reason");
    }
  }

  @Autowired
  protected JdbcTemplate db;

  @Autowired
  protected MockMvc mvc;

  @Autowired
  protected AuthService auth;

  protected String admin;
  protected final Map<RoomCode, String> managers = new EnumMap<>(RoomCode.class);
  protected final Map<RoomCode, Long> managerIds = new EnumMap<>(RoomCode.class);
  protected final Map<RoomCode, Long> rooms = new EnumMap<>(RoomCode.class);
  private int accountCounter;

  @BeforeEach
  void resetEvent() {
    assertEquals("devfest_test", db.queryForObject("SELECT current_database()", String.class));
    db.execute(
      """
      TRUNCATE audit_event, auth_session, refresh_token, rate_bucket, app_user, event_team, room_submission,
               room_judgement, room_judgement_history, collected_clue, penalty, suspect, recovery_attempt,
               accusation, team_final, event_content
      RESTART IDENTITY CASCADE"""
    );
    db.update(
      """
      INSERT INTO event_room(code, name, position) VALUES
       ('AI','AI',0),('PROBLEM_SOLVING','Problem solving',1),('LINUX','Linux',2),('HARDWARE','Hardware',3)
      ON CONFLICT (code) DO NOTHING"""
    );
    db.update(
      "UPDATE event_room SET manager_id = NULL, is_open = FALSE, clue_key = '', recovery_prompt = '', " +
      "recovery_answer = '', version = 1"
    );
    db.update("INSERT INTO event_content(id) VALUES (1)");
    db.update(
      "UPDATE event_state SET current_round = 0, round_started_at = NULL, round_minutes = 25, final_open = FALSE, " +
      "final_started_at = NULL, final_closed_at = NULL, results_published = FALSE, revision = 1, " +
      "planned_team_count = 16, round_phase = NULL, round_duration_seconds = NULL, " +
      "round_elapsed_before_seconds = 0, round_resumed_at = NULL, round_ended = FALSE WHERE id = 1"
    );
    managers.clear();
    managerIds.clear();
    rooms.clear();
    accountCounter = 0;
    for (RoomCode code : RoomCode.values()) {
      rooms.put(code, db.queryForObject("SELECT id FROM event_room WHERE code = ?", Long.class, code.name()));
    }
    admin = login(account("ADMIN"));
    for (RoomCode code : RoomCode.values()) {
      String email = account("MANAGER");
      long id = db.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
      db.update("UPDATE event_room SET manager_id = ? WHERE id = ?", id, rooms.get(code));
      managerIds.put(code, id);
      managers.put(code, login(email));
    }
  }

  // ---- data setup -------------------------------------------------------------------------------

  /** Creates an account with the shared test password and returns its email. */
  protected String account(String role) {
    String email = role.toLowerCase() + (++accountCounter) + "@event.test";
    db.update(
      "INSERT INTO app_user(email, name, password_hash, role) VALUES (?, ?, ?, ?)",
      email,
      role + " " + accountCounter,
      PASSWORD_HASH,
      role
    );
    return email;
  }

  protected String login(String email) {
    return auth.login(email, PASSWORD).accessToken();
  }

  protected TestTeam team(String name, GroupCode group) {
    String email = account("MEMBER");
    long accountId = db.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    long id = db.queryForObject(
      "INSERT INTO event_team(name, group_code, account_id) VALUES (?, ?, ?) RETURNING id",
      Long.class,
      name,
      group.name(),
      accountId
    );
    return new TestTeam(id, name, group, accountId, login(email));
  }

  /** {@code perGroup} teams in each of the groups A..D, named "A1", "A2", ... */
  protected List<TestTeam> teams(int perGroup) {
    List<TestTeam> created = new ArrayList<>();
    for (GroupCode group : GroupCode.values()) {
      for (int i = 1; i <= perGroup; i++) created.add(team(group.name() + i, group));
    }
    return created;
  }

  /**
   * Complete event content: each room's manager sets the clue key and recovery challenge through the API (no
   * key exists in production code), then six suspects, the correct suspect and the message.
   */
  protected void loadContent() {
    for (RoomCode code : RoomCode.values()) {
      expectOk(saveRoomContent(code, managers.get(code), CLUE_KEYS.get(code), "Recovery prompt for " + code, RECOVERY_ANSWERS.get(code)));
    }
    for (int i = 0; i < SUSPECTS.size(); i++) {
      db.update("INSERT INTO suspect(name, description, position) VALUES (?, ?, ?)", SUSPECTS.get(i), "Suspect " + i, i);
    }
    db.update(
      "UPDATE event_content SET encrypted_message = 'WKH SUDFWLFH FOXH', correct_suspect_id = ? WHERE id = 1",
      suspectId(CORRECT_SUSPECT)
    );
  }

  /** PUT /rooms/{id}/content as {@code token}, at the room's current version. */
  protected Response saveRoomContent(RoomCode code, String token, String clueKey, String recoveryPrompt, String recoveryAnswer) {
    String body = "{\"clueKey\":%s,\"recoveryPrompt\":%s,\"recoveryAnswer\":%s,\"version\":%d}".formatted(
      quote(clueKey),
      quote(recoveryPrompt),
      quote(recoveryAnswer),
      roomVersion(code)
    );
    return put("/rooms/" + rooms.get(code) + "/content", token, body);
  }

  /** The room's manager changes only the clue key, keeping the recovery challenge. */
  protected Response setClueKey(RoomCode code, String clueKey) {
    var room = db.queryForMap("SELECT recovery_prompt, recovery_answer FROM event_room WHERE id = ?", rooms.get(code));
    return saveRoomContent(
      code,
      managers.get(code),
      clueKey,
      (String) room.get("recovery_prompt"),
      (String) room.get("recovery_answer")
    );
  }

  protected long roomVersion(RoomCode code) {
    return db.queryForObject("SELECT version FROM event_room WHERE id = ?", Long.class, rooms.get(code));
  }

  protected long suspectId(String name) {
    return db.queryForObject("SELECT id FROM suspect WHERE name = ?", Long.class, name);
  }

  protected long revision() {
    return db.queryForObject("SELECT revision FROM event_state WHERE id = 1", Long.class);
  }

  /** The room a group visits in a round: position (group + round - 1) mod 4. */
  protected static RoomCode roomFor(GroupCode group, int round) {
    return RoomCode.atPosition((group.index() + round - 1) % 4);
  }

  /** The round in which a group visits a room. */
  protected static int roundFor(GroupCode group, RoomCode room) {
    return Math.floorMod(room.position() - group.index(), 4) + 1;
  }

  // ---- admin actions through the API -------------------------------------------------------------

  /** Moves to the round and, for rounds 1..4, starts its timer, so teams can submit right away. */
  protected void setRound(int round) {
    assignRound(round);
    if (round >= 1 && round <= 4) startTimer();
  }

  /** Moves to the round without starting the timer: a running round stays ASSIGNED. */
  protected void assignRound(int round) {
    expectOk(post("/admin/round", admin, "{\"round\":" + round + "}"));
  }

  protected void startTimer() {
    expectOk(post("/admin/round/timer/start", admin, null));
  }

  protected void openRoom(RoomCode code) {
    expectOk(post("/admin/rooms/" + rooms.get(code) + "/open", admin, null));
  }

  protected void closeRoom(RoomCode code) {
    expectOk(post("/admin/rooms/" + rooms.get(code) + "/close", admin, null));
  }

  protected void openFinal() {
    expectOk(post("/admin/final/open", admin, null));
  }

  /** Gives the team a correct room submission in each listed room (switching round and opening the room). */
  protected void collectClues(TestTeam team, RoomCode... codes) {
    for (RoomCode code : codes) {
      setRound(roundFor(team.group(), code));
      openRoom(code);
      Response receipt = submit(team, code, UUID.randomUUID(), "output", CLUE_KEYS.get(code));
      expectOk(receipt);
      assertEquals(true, receipt.json("$.clueCorrect"));
    }
  }

  // ---- team actions through the API --------------------------------------------------------------

  protected Response submit(TestTeam team, RoomCode code, UUID key, String output, String clue) {
    String body = "{\"technicalOutput\":%s,\"clueAnswer\":%s}".formatted(quote(output), quote(clue));
    return teamPost(team, "/team/rooms/" + rooms.get(code) + "/submissions", key, body);
  }

  protected Response recover(TestTeam team, UUID key, String answer) {
    return teamPost(team, "/team/final/recovery", key, "{\"answer\":" + quote(answer) + "}");
  }

  protected Response accuse(TestTeam team, UUID key, String suspect) {
    String body = "{\"suspectId\":%d,\"decryptedMessage\":\"THE PRACTICE CLUE\",\"evidence\":\"Seen at the library\"}";
    return teamPost(team, "/team/final/accusations", key, body.formatted(suspectId(suspect)));
  }

  protected Response teamPost(TestTeam team, String path, UUID key, String body) {
    return call(HttpMethod.POST, path, team.token(), body, key.toString());
  }

  // ---- HTTP --------------------------------------------------------------------------------------

  protected Response get(String path, String token) {
    return call(HttpMethod.GET, path, token, null, null);
  }

  protected Response post(String path, String token, String body) {
    return call(HttpMethod.POST, path, token, body, null);
  }

  protected Response put(String path, String token, String body) {
    return call(HttpMethod.PUT, path, token, body, null);
  }

  protected Response delete(String path, String token) {
    return call(HttpMethod.DELETE, path, token, null, null);
  }

  /** {@code path} is relative to /api/v1/event unless it starts with /api. */
  protected Response call(HttpMethod method, String path, String token, String body, String idempotencyKey) {
    String url = path.startsWith("/api") ? path : "/api/v1/event" + path;
    MockHttpServletRequestBuilder request = request(method, url);
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
    try {
      MvcResult result = mvc.perform(request).andReturn();
      return new Response(
        result.getResponse().getStatus(),
        result.getResponse().getContentAsString(StandardCharsets.UTF_8)
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  protected static Response expectOk(Response response) {
    assertEquals(200, response.status(), response.body());
    return response;
  }

  /** A 401 for an ended session, with its reason code. */
  protected static void expectSessionEnded(Response response, String reason) {
    assertEquals(401, response.status(), response.body());
    assertEquals(reason, response.reason(), response.body());
  }

  protected static void expectError(Response response, int status, String detail) {
    assertEquals(status, response.status(), response.body());
    assertEquals(detail, response.detail());
  }

  protected static String quote(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
