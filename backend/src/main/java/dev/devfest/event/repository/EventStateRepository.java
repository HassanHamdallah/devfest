package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The singleton event_state row: round clock, final, publication and the screen revision.
 *
 * <p>Lock order for every event write (team, manager and admin alike), to rule out deadlocks:
 * the per-team rate_bucket row (team writes only, taken by RateLimit before any lock) →
 * event_state FOR UPDATE ({@link #lockForUpdate()}) → event_team row FOR UPDATE →
 * event_room row FOR UPDATE (only when a room is involved). Writers always take event_state
 * first, so it also serializes every event write.
 */
@Repository
public class EventStateRepository {

  private static final String COLUMNS = """
    current_round, round_started_at, round_minutes, final_open, final_started_at,
    final_closed_at, results_published, revision, updated_at""";

  private final JdbcTemplate db;

  public EventStateRepository(JdbcTemplate db) {
    this.db = db;
  }

  public EventStateRecord state() {
    return db.queryForObject("SELECT " + COLUMNS + " FROM event_state WHERE id = 1", EventStateRepository::map);
  }

  public EventStateRecord lockForUpdate() {
    return db.queryForObject(
      "SELECT " + COLUMNS + " FROM event_state WHERE id = 1 FOR UPDATE",
      EventStateRepository::map
    );
  }

  public long revision() {
    return db.queryForObject("SELECT revision FROM event_state WHERE id = 1", Long.class);
  }

  /** Call once per changing write, inside the writing transaction; returns the new revision. */
  public long bumpRevision() {
    return db.queryForObject(
      "UPDATE event_state SET revision = revision + 1, updated_at = now() WHERE id = 1 RETURNING revision",
      Long.class
    );
  }

  /** {@code roundStartedAt} is null for rounds 0 and 5. */
  public void setRound(int round, Instant roundStartedAt) {
    db.update(
      "UPDATE event_state SET current_round = ?, round_started_at = ? WHERE id = 1",
      round,
      timestamp(roundStartedAt)
    );
  }

  public void setRoundMinutes(int roundMinutes) {
    db.update("UPDATE event_state SET round_minutes = ? WHERE id = 1", roundMinutes);
  }

  public void openFinal(Instant startedAt) {
    db.update(
      "UPDATE event_state SET final_open = TRUE, final_started_at = ?, final_closed_at = NULL WHERE id = 1",
      timestamp(startedAt)
    );
  }

  public void closeFinal(Instant closedAt) {
    db.update(
      "UPDATE event_state SET final_open = FALSE, final_closed_at = ? WHERE id = 1",
      timestamp(closedAt)
    );
  }

  public void setResultsPublished(boolean published) {
    db.update("UPDATE event_state SET results_published = ? WHERE id = 1", published);
  }

  private static EventStateRecord map(ResultSet rs, int row) throws SQLException {
    return new EventStateRecord(
      rs.getInt("current_round"),
      instant(rs, "round_started_at"),
      rs.getInt("round_minutes"),
      rs.getBoolean("final_open"),
      instant(rs, "final_started_at"),
      instant(rs, "final_closed_at"),
      rs.getBoolean("results_published"),
      rs.getLong("revision"),
      instant(rs, "updated_at")
    );
  }
}
