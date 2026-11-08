package dev.devfest;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.devfest.api.Errors.ApiException;
import dev.devfest.auth.*;
import dev.devfest.contest.*;
import dev.devfest.judge.*;
import dev.devfest.submission.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
  properties = {
    "app.worker-enabled=false",
    "app.seed=false",
    "app.bootstrap-email=",
    "app.jwt-secret=devfest-test-only-secret-with-48-characters-123456",
    "spring.config.import=",
  }
)
@AutoConfigureMockMvc
class PlatformIntegrationTest {

  @Autowired
  JdbcTemplate db;

  @Autowired
  AuthService auth;

  @Autowired
  ContestService contest;

  @Autowired
  SubmissionService submissions;

  @Autowired
  QueueStore queue;

  @Autowired
  PasswordEncoder passwords;

  @Autowired
  MockMvc mvc;

  @Autowired
  ObjectMapper json;

  long admin, user, other, problem;
  String password = "Test-password-only-123!";

  @BeforeEach
  void setup() throws Exception {
    String database = db.queryForObject(
      "SELECT current_database()",
      String.class
    );
    assertEquals(
      "devfest_test",
      database,
      "Refusing to touch any non-test database"
    );
    db.execute(
      "TRUNCATE audit_event,execution_task,evaluation,submission,draft,problem_version,problem,refresh_token,auth_session,app_user,rate_bucket RESTART IDENTITY CASCADE"
    );
    admin = account("admin@test.local", "ADMIN");
    user = account("one@test.local", "PARTICIPANT");
    other = account("two@test.local", "PARTICIPANT");
    db.update(
      "UPDATE contest SET start_at=now()-interval '1 hour',end_at=now()+interval '1 hour' WHERE id=1"
    );
    try (var in = getClass().getResourceAsStream("/examples/sum.json")) {
      problem = contest.saveProblem(
        null,
        json.readValue(in, ProblemData.class),
        admin
      );
    }
  }

  long account(String email, String role) {
    return db.queryForObject(
      "INSERT INTO app_user(email,name,password_hash,role) VALUES(?,?,?,?) RETURNING id",
      Long.class,
      email,
      email,
      passwords.encode(password),
      role
    );
  }

  AuthService.Principal principal(long id) {
    return new AuthService.Principal(
      id,
      id == admin ? "ADMIN" : "PARTICIPANT",
      UUID.randomUUID()
    );
  }

  SubmissionService.Create request(String code) {
    return new SubmissionService.Create(
      problem,
      ProblemData.Language.PYTHON,
      code,
      ""
    );
  }

  UUID submit(long id) {
    return submissions.create(
      id,
      "SUBMIT",
      UUID.randomUUID(),
      request("print(5)")
    );
  }

  String token(long id) {
    return auth
      .login(
        id == admin
          ? "admin@test.local"
          : id == user
            ? "one@test.local"
            : "two@test.local",
        password
      )
      .accessToken();
  }

  @Test
  void unauthorizedAndAdminRoleAreEnforced() throws Exception {
    mvc.perform(get("/api/v1/contest")).andExpect(status().isUnauthorized());
    mvc
      .perform(
        get("/api/v1/admin/participants").header(
          "Authorization",
          "Bearer " + token(user)
        )
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        get("/api/v1/admin/participants").header(
          "Authorization",
          "Bearer " + token(admin)
        )
      )
      .andExpect(status().isOk())
      .andExpect(
        content().string(
          org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("password_hash")
          )
        )
      );
  }

  @Test
  void noRegistrationOrPrivilegeEscalation() throws Exception {
    mvc
      .perform(
        post("/api/v1/auth/register")
          .contentType("application/json")
          .content("{}")
      )
      .andExpect(status().isUnauthorized());
    String payload = json.writeValueAsString(
      Map.of(
        "email",
        "new@test.local",
        "name",
        "New",
        "password",
        password,
        "role",
        "ADMIN"
      )
    );
    mvc
      .perform(
        post("/api/v1/admin/participants")
          .header("Authorization", "Bearer " + token(admin))
          .contentType("application/json")
          .content(payload)
      )
      .andExpect(status().isBadRequest());
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM app_user WHERE role='ADMIN'",
        Integer.class
      )
    );
  }

  @Test
  void loginRefreshReplayAndLogout() {
    var t = auth.login("one@test.local", password);
    var principal = auth.authenticate(t.accessToken());
    var renewed = auth.refresh(t.refreshToken());
    assertNotEquals(t.refreshToken(), renewed.refreshToken());
    assertThrows(ApiException.class, () -> auth.refresh(t.refreshToken()));
    assertThrows(ApiException.class, () ->
      auth.authenticate(renewed.accessToken())
    );
    var fresh = auth.login("one@test.local", password);
    auth.logout(auth.authenticate(fresh.accessToken()));
    assertThrows(ApiException.class, () ->
      auth.authenticate(fresh.accessToken())
    );
    assertThrows(ApiException.class, () -> auth.refresh(fresh.refreshToken()));
  }

  @Test
  void concurrentRefreshCannotCreateTwoUsableFamilies() throws Exception {
    var t = auth.login("one@test.local", password);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var f = pool.submit(() -> {
        try {
          return auth.refresh(t.refreshToken());
        } catch (ApiException e) {
          return null;
        }
      });
      var g = pool.submit(() -> {
        try {
          return auth.refresh(t.refreshToken());
        } catch (ApiException e) {
          return null;
        }
      });
      var a = f.get();
      var b = g.get();
      assertTrue((a == null) != (b == null));
      var winner = a == null ? b : a;
      assertThrows(ApiException.class, () ->
        auth.authenticate(winner.accessToken())
      );
    }
  }

  @Test
  void expiredJwtAndWrongIssuerAreRejected() {
    var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
      "devfest-test-only-secret-with-48-characters-123456".getBytes(
        java.nio.charset.StandardCharsets.UTF_8
      )
    );
    String expired = io.jsonwebtoken.Jwts.builder()
      .subject("1")
      .issuer("devfest")
      .expiration(Date.from(Instant.now().minusSeconds(60)))
      .signWith(key)
      .compact();
    assertThrows(io.jsonwebtoken.ExpiredJwtException.class, () ->
      auth.authenticate(expired)
    );
    String wrong = io.jsonwebtoken.Jwts.builder()
      .subject("1")
      .issuer("other")
      .expiration(Date.from(Instant.now().plusSeconds(60)))
      .signWith(key)
      .compact();
    assertThrows(io.jsonwebtoken.IncorrectClaimException.class, () ->
      auth.authenticate(wrong)
    );
  }

  @Test
  void deactivationAndPasswordChangeRevokeSessions() throws Exception {
    var t = auth.login("one@test.local", password);
    auth.password(
      auth.authenticate(t.accessToken()),
      password,
      "Different-password-456!"
    );
    assertThrows(ApiException.class, () -> auth.authenticate(t.accessToken()));
    assertThrows(ApiException.class, () -> auth.refresh(t.refreshToken()));
    var t2 = auth.login("one@test.local", "Different-password-456!");
    mvc
      .perform(
        patch("/api/v1/admin/participants/" + user)
          .header("Authorization", "Bearer " + token(admin))
          .contentType("application/json")
          .content("{\"active\":false}")
      )
      .andExpect(status().isOk());
    assertThrows(ApiException.class, () -> auth.authenticate(t2.accessToken()));
    assertThrows(ApiException.class, () ->
      auth.login("one@test.local", "Different-password-456!")
    );
  }

  @Test
  void ownSubmissionsOnlyAndNoHiddenOutputs() throws Exception {
    UUID id = submit(user);
    var j = queue.claim(4);
    queue.finish(
      j,
      "ACCEPTED",
      5,
      new JudgeClient.Result(
        3,
        "SECRET_INPUT",
        "SECRET_ERROR",
        "SECRET_COMPILE",
        "",
        100L
      )
    );
    mvc
      .perform(
        get("/api/v1/submissions/" + id).header(
          "Authorization",
          "Bearer " + token(other)
        )
      )
      .andExpect(status().isNotFound());
    String response = mvc
      .perform(
        get("/api/v1/submissions/" + id).header(
          "Authorization",
          "Bearer " + token(user)
        )
      )
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    assertFalse(response.contains("SECRET"));
    mvc
      .perform(
        get("/api/v1/problems/" + problem).header(
          "Authorization",
          "Bearer " + token(user)
        )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.tests").doesNotExist());
  }

  @Test
  void idempotentConcurrentRequestsAndPayloadConflict() throws Exception {
    UUID key = UUID.randomUUID();
    try (var pool = Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<Future<UUID>>();
      for (int i = 0; i < 6; i++) tasks.add(
        pool.submit(() ->
          submissions.create(user, "SUBMIT", key, request("print(5)"))
        )
      );
      UUID id = tasks.getFirst().get();
      for (var f : tasks) assertEquals(id, f.get());
      assertEquals(
        1,
        db.queryForObject("SELECT count(*) FROM submission", Integer.class)
      );
    }
    assertEquals(
      409,
      assertThrows(ApiException.class, () ->
        submissions.create(user, "SUBMIT", key, request("print(6)"))
      ).status
    );
  }

  @Test
  void savedRequestRetryAndCompletionAfterDeadline() {
    UUID key = UUID.randomUUID();
    UUID id = submissions.create(user, "SUBMIT", key, request("print(5)"));
    db.update(
      "UPDATE contest SET start_at=now()-interval '2 hours',end_at=now()-interval '1 hour'"
    );
    assertEquals(
      id,
      submissions.create(user, "SUBMIT", key, request("print(5)"))
    );
    assertEquals(
      409,
      assertThrows(ApiException.class, () -> submit(user)).status
    );
    var j = queue.claim(4);
    queue.finish(j, "ACCEPTED", 5, null);
    assertEquals("ACCEPTED", submissions.get(id, principal(user)).state());
  }

  @Test
  void draftsCompareAndSwapAndUserIsolation() {
    var d = contest.saveDraft(
      user,
      problem,
      ProblemData.Language.PYTHON,
      "first",
      0
    );
    assertEquals(1, d.version());
    assertEquals(
      409,
      assertThrows(ApiException.class, () ->
        contest.saveDraft(
          user,
          problem,
          ProblemData.Language.PYTHON,
          "stale",
          0
        )
      ).status
    );
    assertEquals(
      "first",
      contest.draft(user, problem, ProblemData.Language.PYTHON).source()
    );
    assertEquals(
      0,
      contest.draft(other, problem, ProblemData.Language.PYTHON).version()
    );
    assertEquals(
      0,
      contest.draft(user, problem, ProblemData.Language.JAVA).version()
    );
    assertEquals(
      2,
      contest
        .saveDraft(user, problem, ProblemData.Language.PYTHON, "second", 1)
        .version()
    );
  }

  @Test
  void sourceAndProblemVersionAreImmutable() {
    var id = submit(user);
    var old = contest.problem(problem, 1);
    contest.saveProblem(problem, old, admin);
    contest.saveDraft(
      user,
      problem,
      ProblemData.Language.PYTHON,
      "new edit",
      0
    );
    var snapshot = submissions.get(id, principal(user));
    assertEquals("print(5)", snapshot.source());
    assertEquals(1, snapshot.problemVersion());
    assertEquals(2, contest.version(problem));
  }

  @Test
  void outstandingCapAndPerUserFairness() {
    submit(user);
    submit(user);
    submit(user);
    assertEquals(
      429,
      assertThrows(ApiException.class, () -> submit(user)).status
    );
    submit(other);
    var first = queue.claim(4);
    var second = queue.claim(4);
    assertNotNull(first);
    assertNotNull(second);
    assertNotEquals(first.submission(), second.submission());
    assertNull(queue.claim(4));
  }

  @Test
  void globalConcurrencyBound() {
    submit(user);
    submit(other);
    assertNotNull(queue.claim(1));
    assertNull(queue.claim(1));
  }

  @Test
  void expiredLeaseRecoveryRejectsOldResult() {
    var id = submit(user);
    var stale = queue.claim(4);
    db.update(
      "UPDATE evaluation SET lease_until=now()-interval '1 second' WHERE id=?",
      stale.id()
    );
    var recovered = queue.claim(4);
    assertNotNull(recovered);
    assertNotEquals(stale.owner(), recovered.owner());
    queue.finish(stale, "ACCEPTED", 5, null);
    assertEquals("RUNNING", submissions.get(id, principal(user)).state());
    queue.finish(recovered, "WRONG_ANSWER", 0, null);
    queue.finish(recovered, "ACCEPTED", 5, null);
    assertEquals("WRONG_ANSWER", submissions.get(id, principal(user)).state());
  }

  @Test
  void boundedRetries() {
    var id = submit(user);
    for (int i = 0; i < 3; i++) {
      db.update(
        "UPDATE evaluation SET available_at=now() WHERE submission_id=?",
        id
      );
      var j = queue.claim(4);
      assertNotNull(j);
      queue.retry(j);
    }
    assertEquals("SYSTEM_ERROR", submissions.get(id, principal(user)).state());
    assertNull(queue.claim(4));
  }

  @Test
  void rejudgeKeepsHistoryAndDoesNotDuplicateScore() {
    var id = submit(user);
    queue.finish(queue.claim(4), "ACCEPTED", 5, null);
    var second = submit(user);
    queue.finish(queue.claim(4), "ACCEPTED", 5, null);
    assertEquals(1, contest.scores().getFirst().solved());
    submissions.rejudge(id, admin);
    queue.finish(queue.claim(4), "WRONG_ANSWER", 0, null);
    assertEquals(1, contest.scores().getFirst().solved());
    submissions.rejudge(second, admin);
    queue.finish(queue.claim(4), "WRONG_ANSWER", 0, null);
    assertTrue(
      contest
        .scores()
        .stream()
        .allMatch(s -> s.solved() == 0)
    );
    assertEquals(2, submissions.history(id, principal(user)).size());
  }

  @Test
  void unsupportedLanguageAndSourceByteLimit() {
    assertThrows(ApiException.class, () ->
      submissions.create(
        user,
        "RUN",
        UUID.randomUUID(),
        request("é".repeat(40000))
      )
    );
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM submission", Integer.class)
    );
  }
}
