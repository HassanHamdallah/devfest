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

/** Append-only accusations; at most one correct accusation per team (partial unique index). */
@Repository
public class AccusationRepository {

  private final JdbcTemplate db;

  public AccusationRepository(JdbcTemplate db) {
    this.db = db;
  }

  public void insert(AccusationRecord accusation) {
    db.update(
      """
      INSERT INTO accusation(id, team_id, suspect_id, decrypted_message, evidence, correct, received_at,
                             idempotency_key, request_hash)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
      accusation.id(),
      accusation.teamId(),
      accusation.suspectId(),
      accusation.decryptedMessage(),
      accusation.evidence(),
      accusation.correct(),
      timestamp(accusation.receivedAt()),
      accusation.idempotencyKey(),
      accusation.requestHash()
    );
  }

  public Optional<AccusationRecord> findByIdempotencyKey(long teamId, UUID idempotencyKey) {
    return db.query(
      """
      SELECT id, team_id, suspect_id, decrypted_message, evidence, correct, received_at, idempotency_key, request_hash
      FROM accusation WHERE team_id = ? AND idempotency_key = ?""",
      AccusationRepository::map,
      teamId,
      idempotencyKey
    ).stream().findFirst();
  }

  public AccusationCountsRecord countsForTeam(long teamId) {
    return db.queryForObject(
      "SELECT count(*) AS attempts, count(*) FILTER (WHERE NOT correct) AS wrong FROM accusation WHERE team_id = ?",
      (rs, row) -> new AccusationCountsRecord(rs.getInt("attempts"), rs.getInt("wrong")),
      teamId
    );
  }

  public boolean existsForSuspect(long suspectId) {
    return db.queryForObject(
      "SELECT EXISTS(SELECT 1 FROM accusation WHERE suspect_id = ?)",
      Boolean.class,
      suspectId
    );
  }

  private static AccusationRecord map(ResultSet rs, int row) throws SQLException {
    return new AccusationRecord(
      uuid(rs, "id"),
      rs.getLong("team_id"),
      rs.getLong("suspect_id"),
      rs.getString("decrypted_message"),
      rs.getString("evidence"),
      rs.getBoolean("correct"),
      instant(rs, "received_at"),
      uuid(rs, "idempotency_key"),
      rs.getString("request_hash")
    );
  }
}
