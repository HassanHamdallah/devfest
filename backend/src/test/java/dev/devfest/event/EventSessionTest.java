package dev.devfest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.auth.AuthService;
import dev.devfest.event.domain.GroupCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;

/** One signed-in device per team account, revocation reasons on the 401, team sign-out and last-seen throttling. */
class EventSessionTest extends EventIntegrationTest {

  /** A clock the test moves by hand; it replaces the system clock for every bean in this context. */
  static final class ManualClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MICROS));

    void advance(Duration duration) {
      now.updateAndGet(instant -> instant.plus(duration));
    }

    void reset() {
      now.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    @Override
    public Instant instant() {
      return now.get();
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  @TestConfiguration
  static class ManualClockConfiguration {

    @Bean
    @Primary
    ManualClock manualClock() {
      return new ManualClock();
    }
  }

  @Autowired
  private ManualClock clock;

  @BeforeEach
  void resetClock() {
    clock.reset();
  }

  @Test
  void latestMemberSignInWinsAndManagersKeepEverySession() {
    TestTeam team = team("Alpha", GroupCode.A);
    String deviceB = login(email(team));

    Response replaced = get("/team/board", team.token());
    expectSessionEnded(replaced, "REPLACED");
    assertEquals("Your team signed in on another device. Only one device per team can be signed in.", replaced.detail());
    expectOk(get("/team/board", deviceB));

    String manager = account("MANAGER");
    String first = login(manager);
    String second = login(manager);
    expectOk(get("/api/v1/auth/me", first));
    expectOk(get("/api/v1/auth/me", second));
  }

  @Test
  void organizerSignOutEndsTheTeamSession() {
    TestTeam team = team("Alpha", GroupCode.A);
    long revision = revision();
    Response signedOut = expectOk(post("/admin/teams/" + team.id() + "/sign-out", admin, null));
    assertEquals(false, signedOut.json("$.signedIn"));

    Response rejected = get("/team/board", team.token());
    expectSessionEnded(rejected, "ORGANIZER");
    assertEquals("An organizer signed your team out.", rejected.detail());
    assertEquals(revision + 1, revision());
    assertEquals(
      "team:" + team.id(),
      db.queryForObject("SELECT target FROM audit_event WHERE action = 'EVENT_TEAM_SIGNED_OUT'", String.class)
    );
    assertEquals(403, post("/admin/teams/" + team.id() + "/sign-out", managers.values().iterator().next(), null).status());
    assertEquals(404, post("/admin/teams/999999/sign-out", admin, null).status());
  }

  @Test
  void logoutDeactivationAndPasswordResetNameTheirReason() {
    TestTeam team = team("Alpha", GroupCode.A);
    expectOk(post("/api/v1/auth/logout", team.token(), "{}"));
    expectSessionEnded(get("/team/board", team.token()), "LOGOUT");

    String deactivated = login(email(team));
    expectOk(put("/admin/accounts/" + team.accountId() + "/active", admin, "{\"active\":false}"));
    expectSessionEnded(get("/team/board", deactivated), "DEACTIVATED");
    expectOk(put("/admin/accounts/" + team.accountId() + "/active", admin, "{\"active\":true}"));

    String reset = login(email(team));
    expectOk(post("/admin/accounts/" + team.accountId() + "/password", admin, "{\"password\":\"Another-password-123\"}"));
    expectSessionEnded(get("/team/board", reset), "PASSWORD_RESET");

    expectSessionEnded(get("/team/board", "not-a-jwt"), "EXPIRED");
  }

  @Test
  void refreshFromAReplacedSessionIsRefused() {
    TestTeam team = team("Alpha", GroupCode.A);
    AuthService.Tokens deviceA = auth.login(email(team), PASSWORD);
    AuthService.Tokens deviceB = auth.login(email(team), PASSWORD);
    long sessions = sessionCount(team);

    expectSessionEnded(refresh(deviceA.refreshToken()), "REPLACED");
    assertEquals(sessions, sessionCount(team));
    assertEquals(1, (int) db.queryForObject(
      "SELECT count(*) FROM auth_session WHERE user_id = ? AND NOT revoked", Integer.class, team.accountId()
    ));

    expectOk(refresh(deviceB.refreshToken()));
    expectSessionEnded(refresh(deviceB.refreshToken()), "REPLAY");
  }

  @Test
  void lastSeenIsWrittenAtMostOncePerMinute() {
    TestTeam team = team("Alpha", GroupCode.A);
    Instant signedIn = lastSeen(team);
    assertEquals(clock.instant(), signedIn);

    clock.advance(Duration.ofSeconds(30));
    for (int i = 0; i < 3; i++) expectOk(get("/revision", team.token()));
    assertEquals(signedIn, lastSeen(team));

    clock.advance(Duration.ofSeconds(31));
    expectOk(get("/revision", team.token()));
    Instant touched = lastSeen(team);
    assertEquals(clock.instant(), touched);

    clock.advance(Duration.ofSeconds(59));
    for (int i = 0; i < 3; i++) expectOk(get("/revision", team.token()));
    assertEquals(touched, lastSeen(team));
  }

  @Test
  void adminSeesWhetherATeamIsSignedIn() {
    TestTeam team = team("Alpha", GroupCode.A);
    team("Bravo", GroupCode.B);
    db.update("UPDATE auth_session SET revoked = TRUE WHERE user_id = (SELECT account_id FROM event_team WHERE name = 'Bravo')");
    Response org = expectOk(get("/admin/organization", admin));
    assertEquals("Alpha", org.json("$.teams[0].name"));
    assertEquals(true, org.json("$.teams[0].signedIn"));
    assertEquals(false, org.json("$.teams[1].signedIn"));
    assertTrue(org.json("$.teams[1].lastSeenAt") != null, "last seen is kept after sign-out");

    clock.advance(Duration.ofMinutes(3));
    Response later = expectOk(get("/admin/organization", admin));
    assertFalse((Boolean) later.json("$.teams[0].signedIn"), "idle for 3 minutes");
    expectOk(get("/team/board", team.token()));
    assertTrue((Boolean) expectOk(get("/admin/organization", admin)).json("$.teams[0].signedIn"));
  }

  private String email(TestTeam team) {
    return db.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, team.accountId());
  }

  private Response refresh(String refreshToken) {
    return call(HttpMethod.POST, "/api/v1/auth/refresh", null, "{\"refreshToken\":" + quote(refreshToken) + "}", null);
  }

  private long sessionCount(TestTeam team) {
    return db.queryForObject("SELECT count(*) FROM auth_session WHERE user_id = ?", Long.class, team.accountId());
  }

  private Instant lastSeen(TestTeam team) {
    return db
      .queryForObject(
        "SELECT last_seen_at FROM auth_session WHERE user_id = ? AND NOT revoked",
        Timestamp.class,
        team.accountId()
      )
      .toInstant();
  }
}
