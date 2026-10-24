package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.timestamp;

import dev.devfest.event.domain.RoomCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The admin's per-(round, team) room exceptions; rows of earlier rounds stay as history. */
@Repository
public class TeamRoomExceptionRepository {

  private final JdbcTemplate db;

  public TeamRoomExceptionRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** team id → room for every exception of the round (one statement for a whole board). */
  public Map<Long, RoomCode> findForRound(int round) {
    Map<Long, RoomCode> rooms = new HashMap<>();
    db.query(
      "SELECT e.team_id, r.code FROM team_room_exception e JOIN event_room r ON r.id = e.room_id WHERE e.round = ?",
      rs -> {
        rooms.put(rs.getLong("team_id"), RoomCode.valueOf(rs.getString("code")));
      },
      round
    );
    return rooms;
  }

  public Optional<RoomCode> find(int round, long teamId) {
    return db
      .queryForList(
        "SELECT r.code FROM team_room_exception e JOIN event_room r ON r.id = e.room_id WHERE e.round = ? AND e.team_id = ?",
        String.class,
        round,
        teamId
      )
      .stream()
      .findFirst()
      .map(RoomCode::valueOf);
  }

  /** round → room of every exception the team ever had. */
  public Map<Integer, RoomCode> findForTeam(long teamId) {
    Map<Integer, RoomCode> rooms = new HashMap<>();
    db.query(
      "SELECT e.round, r.code FROM team_room_exception e JOIN event_room r ON r.id = e.room_id WHERE e.team_id = ?",
      rs -> {
        rooms.put(rs.getInt("round"), RoomCode.valueOf(rs.getString("code")));
      },
      teamId
    );
    return rooms;
  }

  public void upsert(int round, long teamId, long roomId, String reason, long createdBy, Instant at) {
    db.update(
      """
      INSERT INTO team_room_exception(round, team_id, room_id, reason, created_by, created_at)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT (round, team_id) DO UPDATE
      SET room_id = EXCLUDED.room_id, reason = EXCLUDED.reason, created_by = EXCLUDED.created_by,
          created_at = EXCLUDED.created_at""",
      round,
      teamId,
      roomId,
      reason,
      createdBy,
      timestamp(at)
    );
  }

  /** Returns true when an exception was removed. */
  public boolean delete(int round, long teamId) {
    return db.update("DELETE FROM team_room_exception WHERE round = ? AND team_id = ?", round, teamId) == 1;
  }
}
