package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;
import static dev.devfest.event.repository.EventJdbc.uuid;

import dev.devfest.event.domain.RoomCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only room submissions (the table rejects UPDATE and DELETE). */
@Repository
public class RoomSubmissionRepository {

  private static final String SELECT = """
    SELECT s.id, s.team_id, s.room_id, r.code AS room_code, s.round, s.technical_output, s.clue_answer,
           s.clue_correct, s.received_at, s.idempotency_key, s.request_hash
    FROM room_submission s
    JOIN event_room r ON r.id = s.room_id
    """;

  private final JdbcTemplate db;

  public RoomSubmissionRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** {@code roomCode} of the argument is ignored; it is derived from {@code roomId}. */
  public void insert(RoomSubmissionRecord submission) {
    db.update(
      """
      INSERT INTO room_submission(id, team_id, room_id, round, technical_output, clue_answer,
                                  clue_correct, received_at, idempotency_key, request_hash)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
      submission.id(),
      submission.teamId(),
      submission.roomId(),
      submission.round(),
      submission.technicalOutput(),
      submission.clueAnswer(),
      submission.clueCorrect(),
      timestamp(submission.receivedAt()),
      submission.idempotencyKey(),
      submission.requestHash()
    );
  }

  public Optional<RoomSubmissionRecord> findByIdempotencyKey(long teamId, UUID idempotencyKey) {
    return single(SELECT + "WHERE s.team_id = ? AND s.idempotency_key = ?", teamId, idempotencyKey);
  }

  /** Scoped to the team so another team's id behaves exactly like an unknown id. */
  public Optional<RoomSubmissionRecord> findForTeam(long teamId, UUID submissionId) {
    return single(SELECT + "WHERE s.team_id = ? AND s.id = ?", teamId, submissionId);
  }

  /** Newest first. */
  public List<RoomSubmissionRecord> findRecentForTeam(long teamId, int limit) {
    return db.query(
      SELECT + "WHERE s.team_id = ? ORDER BY s.received_at DESC, s.id DESC LIMIT ?",
      RoomSubmissionRepository::map,
      teamId,
      limit
    );
  }

  private Optional<RoomSubmissionRecord> single(String sql, Object... args) {
    return db.query(sql, RoomSubmissionRepository::map, args).stream().findFirst();
  }

  private static RoomSubmissionRecord map(ResultSet rs, int row) throws SQLException {
    return new RoomSubmissionRecord(
      uuid(rs, "id"),
      rs.getLong("team_id"),
      rs.getLong("room_id"),
      RoomCode.valueOf(rs.getString("room_code")),
      rs.getInt("round"),
      rs.getString("technical_output"),
      rs.getString("clue_answer"),
      rs.getBoolean("clue_correct"),
      instant(rs, "received_at"),
      uuid(rs, "idempotency_key"),
      rs.getString("request_hash")
    );
  }
}
