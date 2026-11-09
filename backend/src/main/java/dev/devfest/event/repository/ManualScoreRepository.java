package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.timestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The manager-typed room scores (0..15) for rooms that have no automatic score. */
@Repository
public class ManualScoreRepository {

  private final JdbcTemplate db;

  public ManualScoreRepository(JdbcTemplate db) {
    this.db = db;
  }

  public void save(long teamId, long roomId, BigDecimal score, long enteredBy, Instant at) {
    db.update(
      """
      INSERT INTO room_manual_score(team_id, room_id, score, entered_by, entered_at) VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (team_id, room_id) DO UPDATE SET score = EXCLUDED.score, entered_by = EXCLUDED.entered_by,
        entered_at = EXCLUDED.entered_at""",
      teamId,
      roomId,
      score,
      enteredBy,
      timestamp(at)
    );
  }

  /** Team id → score for one room. One statement. */
  public Map<Long, BigDecimal> forRoom(long roomId) {
    Map<Long, BigDecimal> scores = new HashMap<>();
    db.query("SELECT team_id, score FROM room_manual_score WHERE room_id = ?", rs -> {
      scores.put(rs.getLong("team_id"), rs.getBigDecimal("score"));
    }, roomId);
    return scores;
  }

  /** Room id → (team id → score), every room. One statement. */
  public Map<Long, Map<Long, BigDecimal>> all() {
    Map<Long, Map<Long, BigDecimal>> scores = new HashMap<>();
    db.query("SELECT room_id, team_id, score FROM room_manual_score", rs -> {
      scores.computeIfAbsent(rs.getLong("room_id"), id -> new HashMap<>()).put(rs.getLong("team_id"), rs.getBigDecimal("score"));
    });
    return scores;
  }
}
