package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;
import static dev.devfest.event.repository.EventJdbc.uuid;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only recovery attempts (the table rejects UPDATE and DELETE). */
@Repository
public class RecoveryAttemptRepository {

  private final JdbcTemplate db;

  public RecoveryAttemptRepository(JdbcTemplate db) {
    this.db = db;
  }

  public void insert(RecoveryAttemptRecord attempt) {
    db.update(
      """
      INSERT INTO recovery_attempt(id, team_id, room_id, answer, correct, received_at, idempotency_key, request_hash)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
      attempt.id(),
      attempt.teamId(),
      attempt.roomId(),
      attempt.answer(),
      attempt.correct(),
      timestamp(attempt.receivedAt()),
      attempt.idempotencyKey(),
      attempt.requestHash()
    );
  }

  public Optional<RecoveryAttemptRecord> findByIdempotencyKey(long teamId, UUID idempotencyKey) {
    return db.query(
      """
      SELECT id, team_id, room_id, answer, correct, received_at, idempotency_key, request_hash
      FROM recovery_attempt WHERE team_id = ? AND idempotency_key = ?""",
      RecoveryAttemptRepository::map,
      teamId,
      idempotencyKey
    ).stream().findFirst();
  }

  public int countForTeam(long teamId) {
    return db.queryForObject("SELECT count(*) FROM recovery_attempt WHERE team_id = ?", Integer.class, teamId);
  }

  private static RecoveryAttemptRecord map(ResultSet rs, int row) throws SQLException {
    return new RecoveryAttemptRecord(
      uuid(rs, "id"),
      rs.getLong("team_id"),
      rs.getLong("room_id"),
      rs.getString("answer"),
      rs.getBoolean("correct"),
      instant(rs, "received_at"),
      uuid(rs, "idempotency_key"),
      rs.getString("request_hash")
    );
  }
}
