package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.timestamp;

import dev.devfest.event.domain.TerminalScoreItem;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The organizer's award or denial of single scored items; no row means automatic. */
@Repository
public class TerminalFormOverrideRepository {

  private final JdbcTemplate db;

  public TerminalFormOverrideRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** Every override of the room by team, in one statement. */
  public Map<Long, Map<TerminalScoreItem, Boolean>> forRoom(long roomId) {
    Map<Long, Map<TerminalScoreItem, Boolean>> byTeam = new HashMap<>();
    db.query(
      "SELECT team_id, item, awarded FROM terminal_form_override WHERE room_id = ?",
      rs -> {
        byTeam
          .computeIfAbsent(rs.getLong("team_id"), team -> new EnumMap<>(TerminalScoreItem.class))
          .put(TerminalScoreItem.valueOf(rs.getString("item")), rs.getBoolean("awarded"));
      },
      roomId
    );
    return byTeam;
  }

  public void set(long teamId, long roomId, TerminalScoreItem item, boolean awarded, long setBy, Instant at) {
    db.update(
      """
      INSERT INTO terminal_form_override(team_id, room_id, item, awarded, set_by, set_at) VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT (team_id, room_id, item) DO UPDATE SET awarded = EXCLUDED.awarded, set_by = EXCLUDED.set_by,
        set_at = EXCLUDED.set_at""",
      teamId,
      roomId,
      item.name(),
      awarded,
      setBy,
      timestamp(at)
    );
  }

  /** Returns true when an override was removed. */
  public boolean clear(long teamId, long roomId, TerminalScoreItem item) {
    return db.update("DELETE FROM terminal_form_override WHERE team_id = ? AND room_id = ? AND item = ?", teamId, roomId, item.name()) == 1;
  }
}
