package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Teams: one member account each, one group each. */
@Repository
public class EventTeamRepository {

  private static final String COLUMNS = "id, name, group_code, account_id, active, created_at, disqualified";

  /**
   * Teams with their member account and presence, in one statement (idx_session_user per team). Signed in:
   * an open session seen within {@link #SIGNED_IN_WINDOW}. Parameters: now, now - window, then the filter.
   */
  private static final String WITH_ACCOUNT = """
    SELECT t.id, t.name, t.group_code, t.account_id, u.email AS account_email, t.active,
           presence.last_seen_at, presence.signed_in, t.disqualified, er.code AS exception_room
    FROM event_team t
    JOIN app_user u ON u.id = t.account_id
    JOIN event_state st ON st.id = 1
    LEFT JOIN team_room_exception ex ON ex.team_id = t.id AND ex.round = st.current_round
    LEFT JOIN event_room er ON er.id = ex.room_id
    CROSS JOIN LATERAL (
      SELECT max(s.last_seen_at) AS last_seen_at,
             coalesce(bool_or(NOT s.revoked AND s.expires_at > ? AND s.last_seen_at >= ?), FALSE) AS signed_in
      FROM auth_session s
      WHERE s.user_id = t.account_id
    ) presence
    """;

  public static final Duration SIGNED_IN_WINDOW = Duration.ofMinutes(2);

  private final JdbcTemplate db;

  public EventTeamRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Optional<EventTeamRecord> find(long teamId) {
    return single("SELECT " + COLUMNS + " FROM event_team WHERE id = ?", teamId);
  }

  /** Lock order: after event_state, before event_room. */
  public Optional<EventTeamRecord> lockForUpdate(long teamId) {
    return single("SELECT " + COLUMNS + " FROM event_team WHERE id = ? FOR UPDATE", teamId);
  }

  public Optional<EventTeamRecord> findByAccount(long accountId) {
    return single("SELECT " + COLUMNS + " FROM event_team WHERE account_id = ?", accountId);
  }

  public Optional<EventTeamRecord> findByName(String name) {
    return single("SELECT " + COLUMNS + " FROM event_team WHERE name = ?", name);
  }

  /** Active teams ordered by name. */
  public List<EventTeamRecord> findActive() {
    return db.query("SELECT " + COLUMNS + " FROM event_team WHERE active ORDER BY name", EventTeamRepository::map);
  }

  public List<TeamAccountRecord> findAllWithAccounts(Instant now) {
    return db.query(
      WITH_ACCOUNT + "ORDER BY t.group_code, t.name",
      EventTeamRepository::mapWithAccount,
      timestamp(now),
      timestamp(now.minus(SIGNED_IN_WINDOW))
    );
  }

  public Optional<TeamAccountRecord> findWithAccount(long teamId, Instant now) {
    return db
      .query(
        WITH_ACCOUNT + "WHERE t.id = ?",
        EventTeamRepository::mapWithAccount,
        timestamp(now),
        timestamp(now.minus(SIGNED_IN_WINDOW)),
        teamId
      )
      .stream()
      .findFirst();
  }

  /** Names of all teams (active or not) that start with {@code prefix}; it holds only letters, digits and spaces. */
  public Set<String> namesStartingWith(String prefix) {
    return new HashSet<>(db.queryForList("SELECT name FROM event_team WHERE name LIKE ?", String.class, prefix + "%"));
  }

  public long count() {
    return db.queryForObject("SELECT count(*) FROM event_team", Long.class);
  }

  /** Active teams in the group other than {@code teamId} (pass 0 when creating a team). */
  public int countOtherActiveInGroup(GroupCode group, long teamId) {
    return db.queryForObject(
      "SELECT count(*) FROM event_team WHERE active AND group_code = ? AND id <> ?",
      Integer.class,
      group.name(),
      teamId
    );
  }

  /** All four groups, including empty ones, in A..D order. */
  public List<GroupSizeRecord> activeGroupSizes() {
    return db.query(
      """
      SELECT g.code, count(t.id) AS active_teams
      FROM (VALUES ('A'), ('B'), ('C'), ('D')) AS g(code)
      LEFT JOIN event_team t ON t.group_code = g.code AND t.active
      GROUP BY g.code
      ORDER BY g.code""",
      (rs, row) -> new GroupSizeRecord(GroupCode.valueOf(rs.getString("code")), rs.getInt("active_teams"))
    );
  }

  public long insert(String name, GroupCode group, long accountId, Instant createdAt) {
    return db.queryForObject(
      "INSERT INTO event_team(name, group_code, account_id, created_at) VALUES (?, ?, ?, ?) RETURNING id",
      Long.class,
      name,
      group.name(),
      accountId,
      timestamp(createdAt)
    );
  }

  /** Locks the teams in ascending id order (the standard lock order for writes touching several teams). */
  public List<EventTeamRecord> lockForUpdate(List<Long> teamIds) {
    return teamIds
      .stream()
      .sorted()
      .map(id -> lockForUpdate(id).orElseThrow(() -> new NoSuchElementException("team " + id)))
      .toList();
  }

  public void setGroup(long teamId, GroupCode group) {
    db.update("UPDATE event_team SET group_code = ? WHERE id = ?", group.name(), teamId);
  }

  /** {@code reason}, {@code at} and {@code by} are null when reinstating. */
  public void setDisqualified(long teamId, boolean disqualified, String reason, Instant at, Long by) {
    db.update(
      """
      UPDATE event_team
      SET disqualified = ?, disqualified_reason = ?, disqualified_at = ?, disqualified_by = ?
      WHERE id = ?""",
      disqualified,
      reason,
      timestamp(at),
      by,
      teamId
    );
  }

  public void update(long teamId, String name, GroupCode group, long accountId, boolean active) {
    db.update(
      "UPDATE event_team SET name = ?, group_code = ?, account_id = ?, active = ? WHERE id = ?",
      name,
      group.name(),
      accountId,
      active,
      teamId
    );
  }

  private Optional<EventTeamRecord> single(String sql, Object... args) {
    return db.query(sql, EventTeamRepository::map, args).stream().findFirst();
  }

  private static EventTeamRecord map(ResultSet rs, int row) throws SQLException {
    return new EventTeamRecord(
      rs.getLong("id"),
      rs.getString("name"),
      GroupCode.valueOf(rs.getString("group_code")),
      rs.getLong("account_id"),
      rs.getBoolean("active"),
      instant(rs, "created_at"),
      rs.getBoolean("disqualified")
    );
  }

  private static TeamAccountRecord mapWithAccount(ResultSet rs, int row) throws SQLException {
    return new TeamAccountRecord(
      rs.getLong("id"),
      rs.getString("name"),
      GroupCode.valueOf(rs.getString("group_code")),
      rs.getLong("account_id"),
      rs.getString("account_email"),
      rs.getBoolean("active"),
      instant(rs, "last_seen_at"),
      rs.getBoolean("signed_in"),
      rs.getBoolean("disqualified"),
      rs.getString("exception_room") == null ? null : RoomCode.valueOf(rs.getString("exception_room"))
    );
  }
}
