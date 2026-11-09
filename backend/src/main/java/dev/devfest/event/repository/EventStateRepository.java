package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.nullableInt;
import static dev.devfest.event.repository.EventJdbc.timestamp;

import dev.devfest.event.domain.RoundPhase;
import dev.devfest.event.domain.RoundTimer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
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
    final_closed_at, results_published, revision, updated_at, planned_team_count, round_phase,
    round_duration_seconds, round_elapsed_before_seconds, round_resumed_at, round_ended, final_phase,
    final_duration_seconds, final_elapsed_before_seconds, final_resumed_at, final_ended""";

  private final JdbcTemplate db;
  private final ApplicationEventPublisher events;

  public EventStateRepository(JdbcTemplate db, ApplicationEventPublisher events) {
    this.db = db;
    this.events = events;
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

  /**
   * Call once per changing write, inside the writing transaction; returns the new revision. The single place
   * that announces a change: {@link RevisionBumped} reaches open screens only after this transaction commits.
   */
  public long bumpRevision() {
    long revision = db.queryForObject(
      "UPDATE event_state SET revision = revision + 1, updated_at = now() WHERE id = 1 RETURNING revision",
      Long.class
    );
    events.publishEvent(new RevisionBumped(revision));
    return revision;
  }

  /**
   * Moves to {@code round}. A running round (1..4) begins ASSIGNED with a fresh {@code timer}; rounds 0 and 5
   * pass null and clear every timer field.
   */
  public void setRound(int round, RoundTimer timer) {
    db.update(
      """
      UPDATE event_state
      SET current_round = ?, round_started_at = NULL, round_phase = ?, round_duration_seconds = ?,
          round_elapsed_before_seconds = 0, round_resumed_at = NULL, round_ended = FALSE
      WHERE id = 1""",
      round,
      timer == null ? null : timer.phase().name(),
      timer == null ? null : timer.durationSeconds()
    );
  }

  /** Stores a timer transition; {@code firstStartedAt} sets round_started_at only if it is still empty. */
  public void saveTimer(RoundTimer timer, Instant firstStartedAt) {
    db.update(
      """
      UPDATE event_state
      SET round_phase = ?, round_duration_seconds = ?, round_elapsed_before_seconds = ?, round_resumed_at = ?,
          round_ended = ?, round_started_at = COALESCE(round_started_at, ?)
      WHERE id = 1""",
      timer.phase().name(),
      timer.durationSeconds(),
      timer.elapsedBeforeSeconds(),
      timestamp(timer.resumedAt()),
      timer.ended(),
      timestamp(firstStartedAt)
    );
  }

  public void setRoundMinutes(int roundMinutes) {
    db.update("UPDATE event_state SET round_minutes = ? WHERE id = 1", roundMinutes);
  }

  /** Opens the final and starts its one timer (RUNNING from {@code startedAt}) for every team at once. */
  public void openFinal(Instant startedAt, int durationSeconds) {
    db.update(
      """
      UPDATE event_state
      SET final_open = TRUE, final_started_at = ?, final_closed_at = NULL, final_phase = 'RUNNING',
          final_duration_seconds = ?, final_elapsed_before_seconds = 0, final_resumed_at = ?, final_ended = FALSE
      WHERE id = 1""",
      timestamp(startedAt),
      durationSeconds,
      timestamp(startedAt)
    );
  }

  public void closeFinal(Instant closedAt) {
    db.update(
      "UPDATE event_state SET final_open = FALSE, final_closed_at = ? WHERE id = 1",
      timestamp(closedAt)
    );
  }

  /** Stores a final-timer transition (pause, resume, added time, end). */
  public void saveFinalTimer(RoundTimer timer) {
    db.update(
      """
      UPDATE event_state
      SET final_phase = ?, final_duration_seconds = ?, final_elapsed_before_seconds = ?, final_resumed_at = ?,
          final_ended = ?
      WHERE id = 1""",
      timer.phase().name(),
      timer.durationSeconds(),
      timer.elapsedBeforeSeconds(),
      timestamp(timer.resumedAt()),
      timer.ended()
    );
  }

  /** Final reset: the final may be opened again (final_started_at is what "ever opened" is derived from). */
  public void resetFinal() {
    db.update(
      """
      UPDATE event_state
      SET final_open = FALSE, final_started_at = NULL, final_closed_at = NULL, final_phase = NULL,
          final_duration_seconds = NULL, final_elapsed_before_seconds = 0, final_resumed_at = NULL, final_ended = FALSE
      WHERE id = 1"""
    );
  }

  /** Event reset: not started, no timer, final never opened, results unpublished. Round length and team count stay. */
  public void resetEvent() {
    db.update(
      """
      UPDATE event_state
      SET current_round = 0, round_started_at = NULL, round_phase = NULL, round_duration_seconds = NULL,
          round_elapsed_before_seconds = 0, round_resumed_at = NULL, round_ended = FALSE, final_open = FALSE,
          final_started_at = NULL, final_closed_at = NULL, results_published = FALSE, final_phase = NULL,
          final_duration_seconds = NULL, final_elapsed_before_seconds = 0, final_resumed_at = NULL, final_ended = FALSE
      WHERE id = 1"""
    );
  }

  public void setPlannedTeamCount(int plannedTeamCount) {
    db.update("UPDATE event_state SET planned_team_count = ? WHERE id = 1", plannedTeamCount);
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
      instant(rs, "updated_at"),
      rs.getInt("planned_team_count"),
      rs.getString("round_phase") == null ? null : RoundPhase.valueOf(rs.getString("round_phase")),
      nullableInt(rs, "round_duration_seconds"),
      rs.getInt("round_elapsed_before_seconds"),
      instant(rs, "round_resumed_at"),
      rs.getBoolean("round_ended"),
      rs.getString("final_phase") == null ? null : RoundPhase.valueOf(rs.getString("final_phase")),
      nullableInt(rs, "final_duration_seconds"),
      rs.getInt("final_elapsed_before_seconds"),
      instant(rs, "final_resumed_at"),
      rs.getBoolean("final_ended")
    );
  }
}
