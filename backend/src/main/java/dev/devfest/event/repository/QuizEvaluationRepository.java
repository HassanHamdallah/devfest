package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;
import static dev.devfest.event.repository.EventJdbc.uuid;

import dev.devfest.event.domain.QuizEvaluationCause;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The teams' AI room evaluations: one row per team and room, never changed (append-only). */
@Repository
public class QuizEvaluationRepository {

  private static final String SELECT = """
    SELECT e.team_id, e.room_id, e.round, e.submission_id, e.total, e.pass_score, e.passed, e.cause, e.evaluated_at,
           EXISTS(SELECT 1 FROM collected_clue c WHERE c.team_id = e.team_id AND c.room_id = e.room_id) AS clue_collected
    FROM quiz_evaluation e
    """;

  private final JdbcTemplate db;

  public QuizEvaluationRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Optional<QuizEvaluationRecord> find(long teamId, long roomId) {
    return db.query(SELECT + "WHERE e.team_id = ? AND e.room_id = ?", QuizEvaluationRepository::map, teamId, roomId).stream().findFirst();
  }

  public List<QuizEvaluationRecord> forRoom(long roomId) {
    return db.query(SELECT + "WHERE e.room_id = ?", QuizEvaluationRepository::map, roomId);
  }

  /** The teams already evaluated in the room. */
  public Set<Long> teamIds(long roomId) {
    return Set.copyOf(db.queryForList("SELECT team_id FROM quiz_evaluation WHERE room_id = ?", Long.class, roomId));
  }

  /** The pass score locks with the room's first evaluation. */
  public boolean anyForRoom(long roomId) {
    return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM quiz_evaluation WHERE room_id = ?)", Boolean.class, roomId));
  }

  /** The primary key (team, room) refuses a second evaluation. */
  public void insert(QuizEvaluationRecord e) {
    db.update(
      """
      INSERT INTO quiz_evaluation(team_id, room_id, round, submission_id, total, pass_score, passed, cause, evaluated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
      e.teamId(),
      e.roomId(),
      e.round(),
      e.submissionId(),
      e.total(),
      e.passScore(),
      e.passed(),
      e.cause().name(),
      timestamp(e.evaluatedAt())
    );
  }

  private static QuizEvaluationRecord map(ResultSet rs, int row) throws SQLException {
    return new QuizEvaluationRecord(
      rs.getLong("team_id"),
      rs.getLong("room_id"),
      rs.getInt("round"),
      uuid(rs, "submission_id"),
      rs.getBigDecimal("total"),
      rs.getInt("pass_score"),
      rs.getBoolean("passed"),
      QuizEvaluationCause.valueOf(rs.getString("cause")),
      instant(rs, "evaluated_at"),
      rs.getBoolean("clue_collected")
    );
  }
}
