package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Arrival confirmations per (round, room). Rows of earlier rounds are kept as history. */
@Repository
public class RoomArrivalRepository {

  private static final String SELECT = """
    SELECT a.round, a.room_id, a.confirmed_by, u.name AS confirmed_by_name, a.confirmed_at
    FROM room_arrival a
    JOIN app_user u ON u.id = a.confirmed_by
    """;

  private final JdbcTemplate db;

  public RoomArrivalRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** Every confirmation of the round (at most one per room). */
  public List<RoomArrivalRecord> findForRound(int round) {
    return db.query(SELECT + "WHERE a.round = ?", (rs, row) -> map(rs), round);
  }

  public Optional<RoomArrivalRecord> find(int round, long roomId) {
    return db.query(SELECT + "WHERE a.round = ? AND a.room_id = ?", (rs, row) -> map(rs), round, roomId).stream().findFirst();
  }

  /** Returns true when the room was not yet confirmed for the round. */
  public boolean confirm(int round, long roomId, long confirmedBy, Instant at) {
    return db.update(
      """
      INSERT INTO room_arrival(round, room_id, confirmed_by, confirmed_at) VALUES (?, ?, ?, ?)
      ON CONFLICT (round, room_id) DO NOTHING""",
      round,
      roomId,
      confirmedBy,
      timestamp(at)
    ) == 1;
  }

  /** Returns true when a confirmation was removed. */
  public boolean withdraw(int round, long roomId) {
    return db.update("DELETE FROM room_arrival WHERE round = ? AND room_id = ?", round, roomId) == 1;
  }

  /** Clears confirmations left from an earlier visit to the same round number, so a new round starts unready. */
  public void clearRound(int round) {
    db.update("DELETE FROM room_arrival WHERE round = ?", round);
  }

  private static RoomArrivalRecord map(ResultSet rs) throws SQLException {
    return new RoomArrivalRecord(
      rs.getInt("round"),
      rs.getLong("room_id"),
      rs.getLong("confirmed_by"),
      rs.getString("confirmed_by_name"),
      instant(rs, "confirmed_at")
    );
  }
}
