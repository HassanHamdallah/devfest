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

  @Transactional
  public Tokens login(String email, String password) {
    var rows = db.queryForList(
      "SELECT * FROM app_user WHERE email=? FOR UPDATE",
      email.trim().toLowerCase(Locale.ROOT)
    );
    if (
      rows.isEmpty() ||
      !passwords.matches(
        password,
        (String) rows.getFirst().get("password_hash")
      ) ||
      !Boolean.TRUE.equals(rows.getFirst().get("active"))
    ) throw fail(401, "Invalid email or password, or inactive account.");
    long id = ((Number) rows.getFirst().get("id")).longValue();
    var session = UUID.randomUUID();
    db.update(
      "INSERT INTO auth_session VALUES(?,?,?,false)",
      session,
      id,
      java.sql.Timestamp.from(clock.instant().plus(Duration.ofHours(12)))
    );
    return issue(id, session);
  }

  // noRollbackFor is intentional: replay revocation must commit even when the caller receives 401.
  @Transactional(noRollbackFor = ApiException.class)
  public Tokens refresh(String token) {
    var rows = db.queryForList(
      "SELECT s.id,s.user_id,s.revoked,s.expires_at,t.used,u.active FROM refresh_token t JOIN auth_session s ON s.id=t.session_id JOIN app_user u ON u.id=s.user_id WHERE t.hash=? FOR UPDATE OF s,t",
      hash(token)
    );
    if (rows.isEmpty()) throw fail(
      401,
      "Session expired. Please sign in again."
    );
    var r = rows.getFirst();
    var sid = (UUID) r.get("id");
    if (
      Boolean.TRUE.equals(r.get("used")) ||
      Boolean.TRUE.equals(r.get("revoked")) ||
      !Boolean.TRUE.equals(r.get("active")) ||
      !((java.sql.Timestamp) r.get("expires_at"))
        .toInstant()
        .isAfter(clock.instant())
    ) {
      db.update("UPDATE auth_session SET revoked=true WHERE id=?", sid);
      throw fail(401, "Session expired. Please sign in again.");
    }
    db.update("UPDATE refresh_token SET used=true WHERE hash=?", hash(token));
    return issue(((Number) r.get("user_id")).longValue(), sid);
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
    var rows = db.queryForList(
      "SELECT u.role FROM app_user u JOIN auth_session s ON s.user_id=u.id WHERE u.id=? AND s.id=? AND u.active AND NOT s.revoked AND s.expires_at>?",
      id,
      sid,
      java.sql.Timestamp.from(clock.instant())
    );
    if (rows.isEmpty()) throw fail(401, "Session expired.");
    return new Principal(id, (String) rows.getFirst().get("role"), sid);
  }

  public void logout(Principal p) {
    db.update("UPDATE auth_session SET revoked=true WHERE id=?", p.session());
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
    db.update("UPDATE auth_session SET revoked=true WHERE user_id=?", id);
  }
}
