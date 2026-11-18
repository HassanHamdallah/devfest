package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The points ledger of the points-and-phone rooms (rules §21.1): rows are only ever appended. */
@Repository
public class RoomPointsRepository {

  private static final String SELECT = """
    SELECT e.id, e.team_id, e.room_id, e.kind, e.delta, e.total_after, e.reverses_id, e.idempotency_key,
           e.entered_by, u.name AS entered_by_name, e.entered_at
    FROM room_points_entry e JOIN app_user u ON u.id = e.entered_by
    """;

  private final JdbcTemplate db;

  public RoomPointsRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** A team's entries in one room, oldest first. */
  public List<PointsEntryRecord> forTeam(long teamId, long roomId) {
    return db.query(SELECT + "WHERE e.team_id = ? AND e.room_id = ? ORDER BY e.id", RoomPointsRepository::map, teamId, roomId);
  }

  /** Every team's entries in one room, oldest first, grouped by team. One statement. */
  public Map<Long, List<PointsEntryRecord>> forRoom(long roomId) {
    return db
      .query(SELECT + "WHERE e.room_id = ? ORDER BY e.id", RoomPointsRepository::map, roomId)
      .stream()
      .collect(Collectors.groupingBy(PointsEntryRecord::teamId, HashMap::new, Collectors.toList()));
  }

  public Optional<PointsEntryRecord> findByKey(UUID idempotencyKey) {
    return db.query(SELECT + "WHERE e.idempotency_key = ?", RoomPointsRepository::map, idempotencyKey).stream().findFirst();
  }

  /** Team id to total (0..100) for one room. One statement. */
  public Map<Long, Integer> totalsForRoom(long roomId) {
    Map<Long, Integer> totals = new HashMap<>();
    db.query("SELECT team_id, SUM(delta) AS total FROM room_points_entry WHERE room_id = ? GROUP BY team_id", rs -> {
      totals.put(rs.getLong("team_id"), rs.getInt("total"));
    }, roomId);
    return totals;
  }

  public void insert(
    long teamId,
    long roomId,
    boolean undo,
    int delta,
    int totalAfter,
    Long reversesId,
    UUID idempotencyKey,
    long enteredBy,
    Instant at
  ) {
    db.update(
      """
      INSERT INTO room_points_entry(team_id, room_id, kind, delta, total_after, reverses_id, idempotency_key, entered_by, entered_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
      teamId,
      roomId,
      undo ? "UNDO" : "ADD",
      delta,
      totalAfter,
      reversesId,
      idempotencyKey,
      enteredBy,
      timestamp(at)
    );
  }

  private static PointsEntryRecord map(ResultSet rs, int row) throws SQLException {
    long reverses = rs.getLong("reverses_id");
    boolean noReverses = rs.wasNull();
    return new PointsEntryRecord(
      rs.getLong("id"),
      rs.getLong("team_id"),
      rs.getLong("room_id"),
      "UNDO".equals(rs.getString("kind")),
      rs.getInt("delta"),
      rs.getInt("total_after"),
      noReverses ? null : reverses,
      rs.getObject("idempotency_key", UUID.class),
      rs.getLong("entered_by"),
      rs.getString("entered_by_name"),
      instant(rs, "entered_at")
    );
  }
}
