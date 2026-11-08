package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;
import static dev.devfest.event.repository.EventJdbc.uuid;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * What teams did in the AI room: experiments (one row per team, stage and configuration) and locked stage
 * submissions (one per team and stage). Both are append-only; JSON columns are read and written as text.
 */
@Repository
public class SentinelWorkRepository {

  public record Run(long teamId, long roomId, int stage, String config, String resultJson, Instant ranAt, UUID idempotencyKey, String requestHash) {}

  public record Submission(
    long teamId,
    long roomId,
    int stage,
    int round,
    String answerJson,
    BigDecimal pointsEarned,
    Instant submittedAt,
    UUID idempotencyKey,
    String requestHash
  ) {}

  /** How far a team got: stages submitted and the sum of their stored, already rounded points. */
  public record Summary(int submitted, BigDecimal total) {}

  private static final String RUN_COLUMNS = """
    SELECT team_id, room_id, stage, config, result::text AS result, ran_at, idempotency_key, request_hash FROM sentinel_run
    """;

  private static final String SUBMISSION_COLUMNS = """
    SELECT team_id, room_id, stage, round, answer::text AS answer, points_earned, submitted_at, idempotency_key, request_hash
    FROM sentinel_submission
    """;

  private final JdbcTemplate db;

  public SentinelWorkRepository(JdbcTemplate db) {
    this.db = db;
  }

  // ---- experiments --------------------------------------------------------------------------------

  public Optional<Run> findRunByKey(long teamId, UUID idempotencyKey) {
    return db.query(RUN_COLUMNS + "WHERE team_id = ? AND idempotency_key = ?", SentinelWorkRepository::mapRun, teamId, idempotencyKey).stream().findFirst();
  }

  /** The team's experiments in the room, in the order they were run. */
  public List<Run> runs(long teamId, long roomId) {
    return db.query(RUN_COLUMNS + "WHERE team_id = ? AND room_id = ? ORDER BY seq", SentinelWorkRepository::mapRun, teamId, roomId);
  }

  public void insertRun(Run run) {
    db.update(
      """
      INSERT INTO sentinel_run(team_id, room_id, stage, config, result, ran_at, idempotency_key, request_hash)
      VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)""",
      run.teamId(),
      run.roomId(),
      run.stage(),
      run.config(),
      run.resultJson(),
      timestamp(run.ranAt()),
      run.idempotencyKey(),
      run.requestHash()
    );
  }

  // ---- submissions --------------------------------------------------------------------------------

  public Optional<Submission> findSubmissionByKey(long teamId, UUID idempotencyKey) {
    return db
      .query(SUBMISSION_COLUMNS + "WHERE team_id = ? AND idempotency_key = ?", SentinelWorkRepository::mapSubmission, teamId, idempotencyKey)
      .stream()
      .findFirst();
  }

  public List<Submission> submissions(long teamId, long roomId) {
    return db.query(SUBMISSION_COLUMNS + "WHERE team_id = ? AND room_id = ? ORDER BY stage", SentinelWorkRepository::mapSubmission, teamId, roomId);
  }

  public List<Submission> roomSubmissions(long roomId) {
    return db.query(SUBMISSION_COLUMNS + "WHERE room_id = ? ORDER BY team_id, stage", SentinelWorkRepository::mapSubmission, roomId);
  }

  /** The primary key (team, stage) is the lock: a second submission of a stage cannot be stored. */
  public void insertSubmission(Submission submission) {
    db.update(
      """
      INSERT INTO sentinel_submission(team_id, room_id, stage, round, answer, points_earned, submitted_at, idempotency_key, request_hash)
      VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""",
      submission.teamId(),
      submission.roomId(),
      submission.stage(),
      submission.round(),
      submission.answerJson(),
      submission.pointsEarned(),
      timestamp(submission.submittedAt()),
      submission.idempotencyKey(),
      submission.requestHash()
    );
  }

  // ---- totals -------------------------------------------------------------------------------------

  public Summary summary(long teamId, long roomId) {
    return db.queryForObject(
      "SELECT count(*) AS submitted, COALESCE(sum(points_earned), 0) AS total FROM sentinel_submission WHERE team_id = ? AND room_id = ?",
      (rs, row) -> new Summary(rs.getInt("submitted"), rs.getBigDecimal("total")),
      teamId,
      roomId
    );
  }

  /** True when any team has run or submitted anything in the room: its content can no longer be replaced. */
  public boolean anyWork(long roomId) {
    return Boolean.TRUE.equals(
      db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM sentinel_run WHERE room_id = ?) OR EXISTS(SELECT 1 FROM sentinel_submission WHERE room_id = ?)",
        Boolean.class,
        roomId,
        roomId
      )
    );
  }

  /** The teams that ran something in the room (for the results page). */
  public List<Long> teamsWithRuns(long roomId) {
    return db.queryForList("SELECT DISTINCT team_id FROM sentinel_run WHERE room_id = ?", Long.class, roomId);
  }

  private static Run mapRun(ResultSet rs, int row) throws SQLException {
    return new Run(
      rs.getLong("team_id"),
      rs.getLong("room_id"),
      rs.getInt("stage"),
      rs.getString("config"),
      rs.getString("result"),
      instant(rs, "ran_at"),
      uuid(rs, "idempotency_key"),
      rs.getString("request_hash")
    );
  }

  private static Submission mapSubmission(ResultSet rs, int row) throws SQLException {
    return new Submission(
      rs.getLong("team_id"),
      rs.getLong("room_id"),
      rs.getInt("stage"),
      rs.getInt("round"),
      rs.getString("answer"),
      rs.getBigDecimal("points_earned"),
      instant(rs, "submitted_at"),
      uuid(rs, "idempotency_key"),
      rs.getString("request_hash")
    );
  }
}
