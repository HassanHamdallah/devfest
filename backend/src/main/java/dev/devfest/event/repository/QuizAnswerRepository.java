package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;
import static dev.devfest.event.repository.EventJdbc.uuid;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The teams' locked AI room answers: one per team and question, one per team and repair (append-only rows). */
@Repository
public class QuizAnswerRepository {

  /**
   * How far a team got in a room: answered questions of all questions, submitted repairs of all repairs, and its
   * total (the sum of its stored, already rounded item scores).
   */
  public record Summary(int answered, int questions, int submitted, int repairs, BigDecimal total) {}

  /** The option a question answer named, with whether it is the question's correct one (null if not its option). */
  public record OptionCheck(int points, Boolean correct) {}

  private static final String MCQ_COLUMNS = """
    SELECT team_id, question_id, option_id, room_id, round, points_earned, answered_at, idempotency_key, request_hash
    FROM quiz_mcq_answer
    """;

  private static final String REPAIR_COLUMNS = """
    SELECT team_id, repair_id, room_id, round, snippet_order, correct_positions, snippet_count, points_earned,
           submitted_at, idempotency_key, request_hash
    FROM quiz_repair_answer
    """;

  private final JdbcTemplate db;

  public QuizAnswerRepository(JdbcTemplate db) {
    this.db = db;
  }

  // ---- multiple choice ----------------------------------------------------------------------------

  public Optional<QuizMcqAnswerRecord> findMcqByKey(long teamId, UUID idempotencyKey) {
    return db.query(MCQ_COLUMNS + "WHERE team_id = ? AND idempotency_key = ?", QuizAnswerRepository::mapMcq, teamId, idempotencyKey)
      .stream()
      .findFirst();
  }

  public boolean mcqAnswered(long teamId, long questionId) {
    return Boolean.TRUE.equals(
      db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM quiz_mcq_answer WHERE team_id = ? AND question_id = ?)",
        Boolean.class,
        teamId,
        questionId
      )
    );
  }

  /** The question's points and whether {@code optionId} is its correct option; empty if the question is not the room's. */
  public Optional<OptionCheck> checkOption(long roomId, long questionId, long optionId) {
    return db
      .query(
        """
        SELECT q.points, (SELECT o.correct FROM quiz_option o WHERE o.id = ? AND o.question_id = q.id) AS correct
        FROM quiz_question q WHERE q.room_id = ? AND q.id = ?""",
        (rs, row) -> new OptionCheck(rs.getInt("points"), (Boolean) rs.getObject("correct")),
        optionId,
        roomId,
        questionId
      )
      .stream()
      .findFirst();
  }

  public void insertMcq(QuizMcqAnswerRecord a) {
    db.update(
      """
      INSERT INTO quiz_mcq_answer(team_id, question_id, option_id, room_id, round, points_earned, answered_at,
                                  idempotency_key, request_hash)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
      a.teamId(),
      a.questionId(),
      a.optionId(),
      a.roomId(),
      a.round(),
      a.pointsEarned(),
      timestamp(a.answeredAt()),
      a.idempotencyKey(),
      a.requestHash()
    );
  }

  public List<QuizMcqAnswerRecord> teamMcqAnswers(long teamId, long roomId) {
    return db.query(MCQ_COLUMNS + "WHERE team_id = ? AND room_id = ?", QuizAnswerRepository::mapMcq, teamId, roomId);
  }

  public List<QuizMcqAnswerRecord> roomMcqAnswers(long roomId) {
    return db.query(MCQ_COLUMNS + "WHERE room_id = ?", QuizAnswerRepository::mapMcq, roomId);
  }

  // ---- repairs ------------------------------------------------------------------------------------

  public Optional<QuizRepairAnswerRecord> findRepairByKey(long teamId, UUID idempotencyKey) {
    return db.query(REPAIR_COLUMNS + "WHERE team_id = ? AND idempotency_key = ?", QuizAnswerRepository::mapRepair, teamId, idempotencyKey)
      .stream()
      .findFirst();
  }

  public boolean repairSubmitted(long teamId, long repairId) {
    return Boolean.TRUE.equals(
      db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM quiz_repair_answer WHERE team_id = ? AND repair_id = ?)",
        Boolean.class,
        teamId,
        repairId
      )
    );
  }

  public void insertRepair(QuizRepairAnswerRecord a) {
    db.update(
      """
      INSERT INTO quiz_repair_answer(team_id, repair_id, room_id, round, snippet_order, correct_positions, snippet_count,
                                     points_earned, submitted_at, idempotency_key, request_hash)
      VALUES (?, ?, ?, ?, CAST(? AS BIGINT[]), ?, ?, ?, ?, ?, ?)""",
      a.teamId(),
      a.repairId(),
      a.roomId(),
      a.round(),
      a.snippetOrder().stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}")),
      a.correctPositions(),
      a.snippetCount(),
      a.pointsEarned(),
      timestamp(a.submittedAt()),
      a.idempotencyKey(),
      a.requestHash()
    );
  }

  public List<QuizRepairAnswerRecord> teamRepairAnswers(long teamId, long roomId) {
    return db.query(REPAIR_COLUMNS + "WHERE team_id = ? AND room_id = ?", QuizAnswerRepository::mapRepair, teamId, roomId);
  }

  public List<QuizRepairAnswerRecord> roomRepairAnswers(long roomId) {
    return db.query(REPAIR_COLUMNS + "WHERE room_id = ?", QuizAnswerRepository::mapRepair, roomId);
  }

  /** One statement: the team's counts and total in the room, for its evaluation. */
  public Summary summary(long teamId, long roomId) {
    return db.queryForObject(
      """
      SELECT m.answered, (SELECT count(*) FROM quiz_question WHERE room_id = ?) AS questions,
             r.submitted, (SELECT count(*) FROM quiz_repair WHERE room_id = ?) AS repairs,
             m.points + r.points AS total
      FROM (SELECT count(*) AS answered, COALESCE(sum(points_earned), 0) AS points
            FROM quiz_mcq_answer WHERE team_id = ? AND room_id = ?) m,
           (SELECT count(*) AS submitted, COALESCE(sum(points_earned), 0) AS points
            FROM quiz_repair_answer WHERE team_id = ? AND room_id = ?) r""",
      (rs, row) ->
        new Summary(
          rs.getInt("answered"),
          rs.getInt("questions"),
          rs.getInt("submitted"),
          rs.getInt("repairs"),
          rs.getBigDecimal("total").setScale(2)
        ),
      roomId,
      roomId,
      teamId,
      roomId,
      teamId,
      roomId
    );
  }

  private static QuizMcqAnswerRecord mapMcq(ResultSet rs, int row) throws SQLException {
    return new QuizMcqAnswerRecord(
      rs.getLong("team_id"),
      rs.getLong("question_id"),
      rs.getLong("option_id"),
      rs.getLong("room_id"),
      rs.getInt("round"),
      rs.getBigDecimal("points_earned"),
      instant(rs, "answered_at"),
      uuid(rs, "idempotency_key"),
      rs.getString("request_hash")
    );
  }

  private static QuizRepairAnswerRecord mapRepair(ResultSet rs, int row) throws SQLException {
    Array order = rs.getArray("snippet_order");
    List<Long> snippetOrder = Arrays.stream((Long[]) order.getArray()).toList();
    order.free();
    return new QuizRepairAnswerRecord(
      rs.getLong("team_id"),
      rs.getLong("repair_id"),
      rs.getLong("room_id"),
      rs.getInt("round"),
      snippetOrder,
      rs.getInt("correct_positions"),
      rs.getInt("snippet_count"),
      rs.getBigDecimal("points_earned"),
      instant(rs, "submitted_at"),
      uuid(rs, "idempotency_key"),
      rs.getString("request_hash")
    );
  }
}
