package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;
import static dev.devfest.event.repository.EventJdbc.uuid;

import dev.devfest.event.domain.RoomCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only room submissions (the table rejects UPDATE and DELETE), read with their current clue verdict. */
@Repository
public class RoomSubmissionRepository {

  private static final String SELECT = """
    SELECT s.id, s.team_id, s.room_id, r.code AS room_code, s.round, s.technical_output, s.clue_answer,
           s.clue_correct, s.received_at, s.idempotency_key, s.request_hash,
           COALESCE(v.effective_correct, s.clue_correct) AS effective_correct
    FROM room_submission s
    JOIN event_room r ON r.id = s.room_id
    LEFT JOIN submission_clue_verdict v ON v.submission_id = s.id
    """;
  private static final String NOT_ACTIVITY_RESULT =
    " AND NOT EXISTS (SELECT 1 FROM quiz_evaluation e WHERE e.submission_id = s.id)" +
    " AND NOT EXISTS (SELECT 1 FROM terminal_form f WHERE f.submission_id = s.id)" +
    " AND NOT EXISTS (SELECT 1 FROM contest_release c WHERE c.submission_id = s.id)";

  private final JdbcTemplate db;

  public RoomSubmissionRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** {@code roomCode} and {@code effectiveCorrect} of the argument are ignored; the verdict row is separate. */
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

  /**
   * Scoped to the team so another team's id behaves exactly like an unknown id. Like {@link #findRecentForTeam},
   * never an AI room result: the team sees only its outcome there (rules §19.7).
   */
  public Optional<RoomSubmissionRecord> findForTeam(long teamId, UUID submissionId) {
    return single(SELECT + "WHERE s.team_id = ? AND s.id = ?" + NOT_ACTIVITY_RESULT, teamId, submissionId);
  }

  /** Codes of the rooms the team ever submitted to. */
  public Set<RoomCode> submittedRooms(long teamId) {
    Set<RoomCode> rooms = EnumSet.noneOf(RoomCode.class);
    db
      .queryForList(
        "SELECT DISTINCT r.code FROM room_submission s JOIN event_room r ON r.id = s.room_id WHERE s.team_id = ?",
        String.class,
        teamId
      )
      .forEach(code -> rooms.add(RoomCode.valueOf(code)));
    return rooms;
  }

  /** The team's own receipts, newest first; AI room results are not receipts. */
  public List<RoomSubmissionRecord> findRecentForTeam(long teamId, int limit) {
    return db.query(
      SELECT + "WHERE s.team_id = ?" + NOT_ACTIVITY_RESULT + " ORDER BY s.received_at DESC, s.id DESC LIMIT ?",
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
      rs.getString("request_hash"),
      rs.getBoolean("effective_correct")
    );
  }
}
