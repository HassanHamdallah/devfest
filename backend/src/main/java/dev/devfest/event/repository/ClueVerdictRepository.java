package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.nullableLong;
import static dev.devfest.event.repository.EventJdbc.timestamp;
import static dev.devfest.event.repository.EventJdbc.uuid;

import dev.devfest.event.domain.ClueOverride;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Current clue verdicts (one per submission) and the append-only history of override changes. The database
 * CHECK keeps effective_correct consistent with auto_correct and the override.
 */
@Repository
public class ClueVerdictRepository {

  private static final String SELECT = """
    SELECT v.submission_id, s.team_id, s.room_id, v.auto_correct, v.override, v.effective_correct,
           v.override_by, u.name AS override_by_name, v.override_at, v.version
    FROM submission_clue_verdict v
    JOIN room_submission s ON s.id = v.submission_id
    LEFT JOIN app_user u ON u.id = v.override_by
    """;

  /** One re-checked submission: its new key-based and effective results. */
  public record Recheck(UUID submissionId, boolean autoCorrect, boolean effectiveCorrect) {}

  private final JdbcTemplate db;

  public ClueVerdictRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** A new submission has no override yet, so its effective result is the key-based one. */
  public void insert(UUID submissionId, boolean autoCorrect, Instant checkedAt) {
    db.update(
      """
      INSERT INTO submission_clue_verdict(submission_id, auto_correct, effective_correct, checked_at)
      VALUES (?, ?, ?, ?)""",
      submissionId,
      autoCorrect,
      autoCorrect,
      timestamp(checkedAt)
    );
  }

  /** Scoped to the room, so a submission of another room behaves like an unknown id. */
  public Optional<ClueVerdictRecord> lockForUpdate(long roomId, UUID submissionId) {
    return db
      .query(SELECT + "WHERE s.room_id = ? AND v.submission_id = ? FOR UPDATE OF v", ClueVerdictRepository::map, roomId, submissionId)
      .stream()
      .findFirst();
  }

  public Optional<ClueVerdictRecord> find(UUID submissionId) {
    return db.query(SELECT + "WHERE v.submission_id = ?", ClueVerdictRepository::map, submissionId).stream().findFirst();
  }

  /** {@code by} and {@code at} are null when the verdict returns to AUTOMATIC. */
  public void setOverride(UUID submissionId, ClueOverride override, boolean effective, Long by, Instant at, long version) {
    db.update(
      """
      UPDATE submission_clue_verdict
      SET override = ?, effective_correct = ?, override_by = ?, override_at = ?, version = ?
      WHERE submission_id = ?""",
      override.stored(),
      effective,
      by,
      timestamp(at),
      version,
      submissionId
    );
  }

  public void insertHistory(
    UUID submissionId,
    ClueOverride oldOverride,
    ClueOverride newOverride,
    boolean oldEffective,
    boolean newEffective,
    long changedBy,
    Instant changedAt,
    long version
  ) {
    db.update(
      """
      INSERT INTO submission_clue_verdict_history(submission_id, old_override, new_override, old_effective,
                                                  new_effective, changed_by, changed_at, version)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
      submissionId,
      oldOverride.stored(),
      newOverride.stored(),
      oldEffective,
      newEffective,
      changedBy,
      timestamp(changedAt),
      version
    );
  }

  /**
   * Every submission of the room with its answer and override, locked for the re-check. One statement. AI room
   * results (quiz_evaluation) are not clue answers and are never re-checked against a key.
   */
  public List<ClueAnswerRecord> answersForRoom(long roomId) {
    return db.query(
      """
      SELECT s.id, s.clue_answer, v.override
      FROM room_submission s
      JOIN submission_clue_verdict v ON v.submission_id = s.id
      WHERE s.room_id = ? AND NOT EXISTS (SELECT 1 FROM quiz_evaluation e WHERE e.submission_id = s.id)
      FOR UPDATE OF v""",
      (rs, row) -> new ClueAnswerRecord(uuid(rs, "id"), rs.getString("clue_answer"), ClueOverride.fromStored(rs.getString("override"))),
      roomId
    );
  }

  /** Writes all re-check results in ONE statement (arrays unnested server-side); returns the rows updated. */
  public int applyRechecks(List<Recheck> rechecks, Instant checkedAt) {
    if (rechecks.isEmpty()) return 0;
    UUID[] ids = rechecks.stream().map(Recheck::submissionId).toArray(UUID[]::new);
    Boolean[] auto = rechecks.stream().map(Recheck::autoCorrect).toArray(Boolean[]::new);
    Boolean[] effective = rechecks.stream().map(Recheck::effectiveCorrect).toArray(Boolean[]::new);
    return db.update(
      """
      UPDATE submission_clue_verdict v
      SET auto_correct = x.auto_correct, effective_correct = x.effective_correct, checked_at = ?
      FROM unnest(?::uuid[], ?::boolean[], ?::boolean[]) AS x(submission_id, auto_correct, effective_correct)
      WHERE v.submission_id = x.submission_id""",
      ps -> {
        ps.setObject(1, timestamp(checkedAt));
        ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids));
        ps.setArray(3, ps.getConnection().createArrayOf("boolean", auto));
        ps.setArray(4, ps.getConnection().createArrayOf("boolean", effective));
      }
    );
  }

  private static ClueVerdictRecord map(ResultSet rs, int row) throws SQLException {
    return new ClueVerdictRecord(
      uuid(rs, "submission_id"),
      rs.getLong("team_id"),
      rs.getLong("room_id"),
      rs.getBoolean("auto_correct"),
      ClueOverride.fromStored(rs.getString("override")),
      rs.getBoolean("effective_correct"),
      nullableLong(rs, "override_by"),
      rs.getString("override_by_name"),
      instant(rs, "override_at"),
      rs.getLong("version")
    );
  }
}
