package dev.devfest.auth;

import static dev.devfest.api.Errors.fail;

import dev.devfest.api.Errors.ApiException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  public record User(long id, String email, String name, String role) {}

  public record Principal(long id, String role, UUID session) {}

  public record Tokens(String accessToken, String refreshToken, User user) {}

  /** Team accounts: one signed-in device at a time (latest sign-in wins). */
  private static final String SINGLE_SESSION_ROLE = "MEMBER";
  /** last_seen_at is refreshed at most this often per session. */
  public static final Duration LAST_SEEN_INTERVAL = Duration.ofSeconds(60);

  private final JdbcTemplate db;
  private final PasswordEncoder passwords;
  private final javax.crypto.SecretKey key;
  private final Clock clock;

  public AuthService(
    JdbcTemplate db,
    PasswordEncoder passwords,
    @Value("${app.jwt-secret}") String secret,
    Clock clock
  ) {
    this.db = db;
    this.passwords = passwords;
    this.clock = clock;
    if (
      secret.length() < 32 || secret.contains("REPLACE_")
    ) throw new IllegalStateException(
      "Set a unique DEVFEST_JWT_SECRET of at least 32 characters"
    );
    key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  public static String hash(String s) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          s.getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public User user(long id) {
    return db.queryForObject(
      "SELECT id,email,name,role FROM app_user WHERE id=?",
      (r, n) ->
        new User(r.getLong(1), r.getString(2), r.getString(3), r.getString(4)),
      id
    );
  }

  /**
   * A MEMBER (team) account keeps one session: signing in revokes its other sessions as REPLACED, in this
   * transaction, while the user row lock serializes concurrent sign-ins. A member whose event team is
   * deactivated cannot sign in.
   */
  @Transactional
  public Tokens login(String email, String password) {
    var rows = db.queryForList(
      "SELECT u.*,EXISTS(SELECT 1 FROM event_team t WHERE t.account_id=u.id AND NOT t.active) AS team_inactive FROM app_user u WHERE u.email=? FOR UPDATE OF u",
      email.trim().toLowerCase(Locale.ROOT)
    );
    if (
      rows.isEmpty() ||
      !passwords.matches(
        password,
        (String) rows.getFirst().get("password_hash")
      ) ||
      !Boolean.TRUE.equals(rows.getFirst().get("active")) ||
      Boolean.TRUE.equals(rows.getFirst().get("team_inactive"))
    ) throw fail(401, "Invalid email or password, or inactive account.");
    long id = ((Number) rows.getFirst().get("id")).longValue();
    var session = UUID.randomUUID();
    Instant now = clock.instant();
    db.update(
      "INSERT INTO auth_session(id,user_id,expires_at,revoked,last_seen_at) VALUES(?,?,?,false,?)",
      session,
      id,
      java.sql.Timestamp.from(now.plus(Duration.ofHours(12))),
      java.sql.Timestamp.from(now)
    );
    if (SINGLE_SESSION_ROLE.equals(rows.getFirst().get("role"))) db.update(
      "UPDATE auth_session SET revoked=true,revoked_reason=? WHERE user_id=? AND id<>? AND NOT revoked",
      SessionEnd.REPLACED.name(),
      id,
      session
    );
    return issue(id, session);
  }

  // noRollbackFor is intentional: replay revocation must commit even when the caller receives 401.
  @Transactional(noRollbackFor = ApiException.class)
  public Tokens refresh(String token) {
    var rows = db.queryForList(
      "SELECT s.id,s.user_id,s.revoked,s.revoked_reason,s.expires_at,t.used,u.active FROM refresh_token t JOIN auth_session s ON s.id=t.session_id JOIN app_user u ON u.id=s.user_id WHERE t.hash=? FOR UPDATE OF s,t",
      hash(token)
    );
    if (rows.isEmpty()) throw new SessionEndedException(SessionEnd.EXPIRED);
    var r = rows.getFirst();
    var sid = (UUID) r.get("id");
    if (Boolean.TRUE.equals(r.get("revoked"))) throw new SessionEndedException(
      SessionEnd.fromStored((String) r.get("revoked_reason"))
    );
    if (!Boolean.TRUE.equals(r.get("active"))) throw revoke(sid, SessionEnd.DEACTIVATED);
    if (
      !((java.sql.Timestamp) r.get("expires_at"))
        .toInstant()
        .isAfter(clock.instant())
    ) {
      db.update("UPDATE auth_session SET revoked=true WHERE id=?", sid);
      throw new SessionEndedException(SessionEnd.EXPIRED);
    }
    if (Boolean.TRUE.equals(r.get("used"))) throw revoke(sid, SessionEnd.REPLAY);
    db.update("UPDATE refresh_token SET used=true WHERE hash=?", hash(token));
    return issue(((Number) r.get("user_id")).longValue(), sid);
  }

  private SessionEndedException revoke(UUID sid, SessionEnd reason) {
    db.update(
      "UPDATE auth_session SET revoked=true,revoked_reason=? WHERE id=? AND NOT revoked",
      reason.name(),
      sid
    );
    return new SessionEndedException(reason);
  }

  /** Ends every open session of the account; sessions that already ended keep their first reason. */
  public void revokeAll(long userId, SessionEnd reason) {
    db.update(
      "UPDATE auth_session SET revoked=true,revoked_reason=? WHERE user_id=? AND NOT revoked",
      reason.name(),
      userId
    );
  }

  private Tokens issue(long id, UUID sid) {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    db.update(
      "INSERT INTO refresh_token(hash,session_id) VALUES(?,?)",
      hash(raw),
      sid
    );
    Instant now = clock.instant();
    String access = Jwts.builder()
      .subject(Long.toString(id))
      .issuer("devfest")
      .claim("sid", sid.toString())
      .issuedAt(Date.from(now))
      .expiration(Date.from(now.plusSeconds(900)))
      .signWith(key)
      .compact();
    return new Tokens(access, raw, user(id));
  }

  private record SessionCheck(
    String role,
    boolean active,
    boolean revoked,
    String revokedReason,
    Instant expiresAt,
    Instant lastSeenAt
  ) {}

  /**
   * One primary-key query per request. A valid session records last_seen_at at most once per
   * {@link #LAST_SEEN_INTERVAL}, so steady polling adds no write per request.
   */
  public Principal authenticate(String token) {
    var c = Jwts.parser()
      .verifyWith(key)
      .requireIssuer("devfest")
      .clock(() -> Date.from(clock.instant()))
      .build()
      .parseSignedClaims(token)
      .getPayload();
    long id = Long.parseLong(c.getSubject());
    UUID sid = UUID.fromString(c.get("sid", String.class));
    var rows = db.query(
      "SELECT u.role,u.active,s.revoked,s.revoked_reason,s.expires_at,s.last_seen_at FROM auth_session s JOIN app_user u ON u.id=s.user_id WHERE s.id=? AND s.user_id=?",
      (r, n) ->
        new SessionCheck(
          r.getString(1),
          r.getBoolean(2),
          r.getBoolean(3),
          r.getString(4),
          r.getTimestamp(5).toInstant(),
          r.getTimestamp(6) == null ? null : r.getTimestamp(6).toInstant()
        ),
      sid,
      id
    );
    if (rows.isEmpty()) throw new SessionEndedException(SessionEnd.EXPIRED);
    var s = rows.getFirst();
    Instant now = clock.instant();
    if (s.revoked()) throw new SessionEndedException(SessionEnd.fromStored(s.revokedReason()));
    if (!s.active()) throw new SessionEndedException(SessionEnd.DEACTIVATED);
    if (!s.expiresAt().isAfter(now)) throw new SessionEndedException(SessionEnd.EXPIRED);
    Instant staleBefore = now.minus(LAST_SEEN_INTERVAL);
    if (s.lastSeenAt() == null || s.lastSeenAt().isBefore(staleBefore)) db.update(
      "UPDATE auth_session SET last_seen_at=? WHERE id=? AND (last_seen_at IS NULL OR last_seen_at<?)",
      java.sql.Timestamp.from(now),
      sid,
      java.sql.Timestamp.from(staleBefore)
    );
    return new Principal(id, s.role(), sid);
  }

  public void logout(Principal p) {
    revoke(p.session(), SessionEnd.LOGOUT);
  }

  /** Re-checks an account's own password for a dangerous action; nothing is logged. */
  public boolean passwordMatches(long userId, String password) {
    String stored = db.queryForObject("SELECT password_hash FROM app_user WHERE id=?", String.class, userId);
    return passwords.matches(password, stored);
  }

  @Transactional
  public void password(Principal p, String current, String replacement) {
    String stored = db.queryForObject(
      "SELECT password_hash FROM app_user WHERE id=? FOR UPDATE",
      String.class,
      p.id()
    );
    if (!passwords.matches(current, stored)) throw fail(
      400,
      "Current password is incorrect."
    );
    reset(p.id(), replacement);
  }

  @Transactional
  public void reset(long id, String password) {
    if (
      password.getBytes(StandardCharsets.UTF_8).length > 72 ||
      password.length() < 12
    ) throw fail(400, "Use 12–72 password bytes.");
    db.update(
      "UPDATE app_user SET password_hash=? WHERE id=?",
      passwords.encode(password),
      id
    );
    revokeAll(id, SessionEnd.PASSWORD_RESET);
  }
}
