package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.nullableLong;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Event staff accounts (MANAGER, MEMBER) in app_user; ADMIN and PARTICIPANT rows are never returned. */
@Repository
public class EventAccountRepository {

  public static final String MANAGER = "MANAGER";
  public static final String MEMBER = "MEMBER";

  private static final String SELECT = """
    SELECT u.id, u.email, u.name, u.role, u.active, t.id AS team_id, r.id AS room_id
    FROM app_user u
    LEFT JOIN event_team t ON t.account_id = u.id
    LEFT JOIN event_room r ON r.manager_id = u.id
    WHERE u.role IN ('MANAGER', 'MEMBER')
    """;

  private final JdbcTemplate db;

  public EventAccountRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** Ordered by role, then email. */
  public List<EventAccountRecord> findAll() {
    return db.query(SELECT + "ORDER BY u.role, u.email", EventAccountRepository::map);
  }

  public Optional<EventAccountRecord> find(long accountId) {
    return db.query(SELECT + "AND u.id = ?", EventAccountRepository::map, accountId).stream().findFirst();
  }

  /** {@code email} must already be normalized (trimmed, lower case). */
  public Optional<EventAccountRecord> findByEmail(String email) {
    return db.query(SELECT + "AND u.email = ?", EventAccountRepository::map, email).stream().findFirst();
  }

  /** Any role, so a duplicate check also catches ADMIN and PARTICIPANT emails. */
  public boolean emailExists(String email) {
    return db.queryForObject("SELECT EXISTS(SELECT 1 FROM app_user WHERE email = ?)", Boolean.class, email);
  }

  /** {@code role} is MANAGER or MEMBER; the caller hashes the password. */
  public long insert(String email, String name, String passwordHash, String role) {
    return db.queryForObject(
      "INSERT INTO app_user(email, name, password_hash, role) VALUES (?, ?, ?, ?) RETURNING id",
      Long.class,
      email,
      name,
      passwordHash,
      role
    );
  }

  public void setActive(long accountId, boolean active) {
    db.update("UPDATE app_user SET active = ? WHERE id = ? AND role IN ('MANAGER', 'MEMBER')", active, accountId);
  }

  public void revokeSessions(long accountId) {
    db.update("UPDATE auth_session SET revoked = TRUE WHERE user_id = ?", accountId);
  }

  private static EventAccountRecord map(ResultSet rs, int row) throws SQLException {
    return new EventAccountRecord(
      rs.getLong("id"),
      rs.getString("email"),
      rs.getString("name"),
      rs.getString("role"),
      rs.getBoolean("active"),
      nullableLong(rs, "team_id"),
      nullableLong(rs, "room_id")
    );
  }
}
