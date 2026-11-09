package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;

import dev.devfest.event.domain.DeductionKind;
import dev.devfest.event.domain.RoomCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The deduction log: rows are never deleted; the database allows exactly one change per row, its cancellation. */
@Repository
public class DeductionRepository {

  /** Active deductions of one team in one room (what the room score subtracts). */
  public record ActiveCount(long teamId, long roomId, int hints, boolean cheating) {}

  private static final String SELECT = """
    SELECT d.id, d.team_id, t.name AS team_name, d.room_id, r.code AS room_code, d.kind, d.reason,
           c.name AS created_by_name, d.created_at, x.name AS cancelled_by_name, d.cancelled_at, d.cancel_reason
    FROM score_deduction d
    JOIN event_team t ON t.id = d.team_id
    JOIN event_room r ON r.id = d.room_id
    JOIN app_user c ON c.id = d.created_by
    LEFT JOIN app_user x ON x.id = d.cancelled_by
    """;

  private static final String NEWEST_FIRST = " ORDER BY d.created_at DESC, d.id DESC";

  private final JdbcTemplate db;

  public DeductionRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Optional<DeductionRecord> find(long id) {
    return db.query(SELECT + "WHERE d.id = ?", DeductionRepository::map, id).stream().findFirst();
  }

  /** The whole log, newest first (admin). */
  public List<DeductionRecord> findAll() {
    return db.query(SELECT + NEWEST_FIRST, DeductionRepository::map);
  }

  /** Every team's rows in the room, newest first, active and cancelled (the room board and its log). One statement. */
  public List<DeductionRecord> findForRoom(long roomId) {
    return db.query(SELECT + "WHERE d.room_id = ?" + NEWEST_FIRST, DeductionRepository::map, roomId);
  }

  public long insert(long teamId, long roomId, DeductionKind kind, String reason, long createdBy, Instant at) {
    return db.queryForObject(
      """
      INSERT INTO score_deduction(team_id, room_id, kind, reason, created_by, created_at)
      VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
      Long.class,
      teamId,
      roomId,
      kind.name(),
      reason,
      createdBy,
      timestamp(at)
    );
  }

  /** False when the row does not exist or is already cancelled. */
  public boolean cancel(long id, long cancelledBy, Instant at, String reason) {
    return db.update(
      "UPDATE score_deduction SET cancelled_by = ?, cancelled_at = ?, cancel_reason = ? WHERE id = ? AND cancelled_at IS NULL",
      cancelledBy,
      timestamp(at),
      reason,
      id
    ) == 1;
  }

  /** The team's active hints across all four rooms. */
  public int activeHints(long teamId) {
    return db.queryForObject(
      "SELECT count(*) FROM score_deduction WHERE team_id = ? AND kind = 'HINT' AND cancelled_at IS NULL",
      Integer.class,
      teamId
    );
  }

  public boolean activeCheating(long teamId, long roomId) {
    return db.queryForObject(
      "SELECT EXISTS(SELECT 1 FROM score_deduction WHERE team_id = ? AND room_id = ? AND kind = 'CHEATING' AND cancelled_at IS NULL)",
      Boolean.class,
      teamId,
      roomId
    );
  }

  /** Active hints per team across all rooms (team id → count); teams without any are absent. One statement. */
  public Map<Long, Integer> activeHintsByTeam() {
    Map<Long, Integer> hints = new HashMap<>();
    db.query(
      "SELECT team_id, count(*) AS n FROM score_deduction WHERE kind = 'HINT' AND cancelled_at IS NULL GROUP BY team_id",
      rs -> {
        hints.put(rs.getLong("team_id"), rs.getInt("n"));
      }
    );
    return hints;
  }

  /** Active hints and cheating per team and room, for the room scores of every team. One statement. */
  public List<ActiveCount> activeCounts() {
    return db.query(
      """
      SELECT team_id, room_id, count(*) FILTER (WHERE kind = 'HINT') AS hints,
             bool_or(kind = 'CHEATING') AS cheating
      FROM score_deduction WHERE cancelled_at IS NULL GROUP BY team_id, room_id""",
      (rs, row) -> new ActiveCount(rs.getLong("team_id"), rs.getLong("room_id"), rs.getInt("hints"), rs.getBoolean("cheating"))
    );
  }

  private static DeductionRecord map(ResultSet rs, int row) throws SQLException {
    return new DeductionRecord(
      rs.getLong("id"),
      rs.getLong("team_id"),
      rs.getString("team_name"),
      rs.getLong("room_id"),
      RoomCode.valueOf(rs.getString("room_code")),
      DeductionKind.valueOf(rs.getString("kind")),
      rs.getString("reason"),
      rs.getString("created_by_name"),
      instant(rs, "created_at"),
      rs.getString("cancelled_by_name"),
      instant(rs, "cancelled_at"),
      rs.getString("cancel_reason")
    );
  }
}
