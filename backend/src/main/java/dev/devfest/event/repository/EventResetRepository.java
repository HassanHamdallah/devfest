package dev.devfest.event.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bulk deletes for the admin's resets, in foreign-key-safe order (one statement per table, never per row).
 * Append-only tables reject DELETE unless {@link #allowResetDeletes()} ran in the same transaction; callers
 * must save a snapshot first (EventSnapshotRepository) in that transaction.
 */
@Repository
public class EventResetRepository {

  /** Every team-progress table, children before parents; {@code %s} is the per-table team filter or "TRUE". */
  private static final List<String> TEAM_PROGRESS_DELETES = List.of(
    "DELETE FROM team_final WHERE %s",
    "DELETE FROM accusation WHERE %s",
    "DELETE FROM recovery_attempt WHERE %s",
    "DELETE FROM collected_clue WHERE %s",
    "DELETE FROM submission_clue_verdict_history WHERE submission_id IN (SELECT id FROM room_submission WHERE %s)",
    "DELETE FROM submission_clue_verdict WHERE submission_id IN (SELECT id FROM room_submission WHERE %s)",
    "DELETE FROM room_submission WHERE %s",
    "DELETE FROM room_judgement_history WHERE %s",
    "DELETE FROM room_judgement WHERE %s",
    "DELETE FROM penalty WHERE %s",
    "DELETE FROM team_room_exception WHERE %s"
  );

  private final JdbcTemplate db;

  public EventResetRepository(JdbcTemplate db) {
    this.db = db;
  }

  /**
   * Lets this transaction delete append-only rows (transaction-local: it ends with the commit or rollback).
   * Only the reset service calls this; UPDATE stays forbidden by the triggers regardless.
   */
  public void allowResetDeletes() {
    db.queryForObject("SELECT set_config('devfest.allow_reset', 'on', true)", String.class);
  }

  /** All final-phase progress: finishes, accusations, recovery attempts, RECOVERY clues and automatic penalties. */
  public void deleteFinalProgress() {
    db.update("DELETE FROM team_final");
    db.update("DELETE FROM accusation");
    db.update("DELETE FROM recovery_attempt");
    db.update("DELETE FROM collected_clue WHERE source = 'RECOVERY'");
    db.update("DELETE FROM penalty WHERE kind IN ('RECOVERY', 'WRONG_ACCUSATION')");
  }

  /** Everything one team did; the team, its account and its group stay. */
  public void deleteTeamProgress(long teamId) {
    for (String statement : TEAM_PROGRESS_DELETES) db.update(statement.formatted("team_id = ?"), teamId);
  }

  /** Everything every team did, plus the rounds' arrivals and time adjustments. */
  public void deleteAllProgress() {
    for (String statement : TEAM_PROGRESS_DELETES) db.update(statement.formatted("TRUE"));
    db.update("DELETE FROM room_arrival");
    db.update("DELETE FROM round_time_adjustment");
  }
}
