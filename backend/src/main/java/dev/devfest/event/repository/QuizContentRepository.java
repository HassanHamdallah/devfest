package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;

import dev.devfest.event.domain.QuizSection;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The AI room's authored content: questions with options, repairs with snippets. Each read is one statement.
 * Options and snippets are inserted in a random order so their ids say nothing about which option is correct or
 * where a snippet belongs.
 */
@Repository
public class QuizContentRepository {

  /** Per room with an activity: question counts per section, repairs and total points (admin alerts). */
  public record RoomStats(
    long roomId,
    String roomName,
    boolean hasClueKey,
    Map<QuizSection, Integer> questionsBySection,
    int repairs,
    int totalPoints
  ) {
    public int questions() {
      return questionsBySection.values().stream().mapToInt(Integer::intValue).sum();
    }
  }

  /** An option to store: authoring position, text and whether it is the correct one. */
  public record NewOption(int position, String text, boolean correct) {}

  private static final String QUESTIONS_SQL = """
    SELECT q.id, q.room_id, q.section, q.position, q.prompt, q.points, q.explanation, q.updated_at,
           EXISTS(SELECT 1 FROM quiz_mcq_answer a WHERE a.question_id = q.id) AS answered,
           o.id AS option_id, o.position AS option_position, o.text AS option_text, o.correct
    FROM quiz_question q
    LEFT JOIN quiz_option o ON o.question_id = q.id
    WHERE q.room_id = ? %s
    ORDER BY q.position, q.id, o.position
    """;

  private static final String REPAIRS_SQL = """
    SELECT r.id, r.room_id, r.position, r.title, r.instructions, r.points, r.updated_at,
           EXISTS(SELECT 1 FROM quiz_repair_answer a WHERE a.repair_id = r.id) AS answered,
           s.id AS snippet_id, s.correct_position, s.code
    FROM quiz_repair r
    LEFT JOIN quiz_snippet s ON s.repair_id = r.id
    WHERE r.room_id = ? %s
    ORDER BY r.position, r.id, s.correct_position
    """;

  // "~ '\\S'" = the key contains a non-whitespace character, as on the admin board.
  private static final String STATS_SQL = """
    SELECT r.id, r.name, r.clue_key ~ '\\S' AS has_clue_key,
           count(q.id) FILTER (WHERE q.section = 'EASY') AS easy,
           count(q.id) FILTER (WHERE q.section = 'MEDIUM') AS medium,
           count(q.id) FILTER (WHERE q.section = 'ADVANCED') AS advanced,
           count(q.id) FILTER (WHERE q.section = 'LANDMARK') AS landmark,
           count(q.id) FILTER (WHERE q.section = 'RECENT') AS recent,
           COALESCE(sum(q.points), 0) AS question_points,
           (SELECT count(*) FROM quiz_repair p WHERE p.room_id = r.id) AS repairs,
           (SELECT COALESCE(sum(p.points), 0) FROM quiz_repair p WHERE p.room_id = r.id) AS repair_points
    FROM event_room r
    LEFT JOIN quiz_question q ON q.room_id = r.id
    WHERE r.activity = 'AI_QUIZ'
    GROUP BY r.id
    ORDER BY r.position
    """;

  private final JdbcTemplate db;
  private final RandomGenerator random = new SecureRandom();

  public QuizContentRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** Every question of the room with its options, in authoring order. */
  public List<QuizQuestionRecord> questions(long roomId) {
    return db.query(QUESTIONS_SQL.formatted(""), QuizContentRepository::questionRows, roomId);
  }

  public Optional<QuizQuestionRecord> question(long roomId, long questionId) {
    return db.query(QUESTIONS_SQL.formatted("AND q.id = ?"), QuizContentRepository::questionRows, roomId, questionId).stream().findFirst();
  }

  /** Every repair of the room with its snippets in their correct order, in authoring order. */
  public List<QuizRepairRecord> repairs(long roomId) {
    return db.query(REPAIRS_SQL.formatted(""), QuizContentRepository::repairRows, roomId);
  }

  public Optional<QuizRepairRecord> repair(long roomId, long repairId) {
    return db.query(REPAIRS_SQL.formatted("AND r.id = ?"), QuizContentRepository::repairRows, roomId, repairId).stream().findFirst();
  }

  /** True when any team has answered any question or repair of the room. */
  public boolean anyAnswers(long roomId) {
    return Boolean.TRUE.equals(
      db.queryForObject(
        """
        SELECT EXISTS(SELECT 1 FROM quiz_mcq_answer WHERE room_id = ?)
            OR EXISTS(SELECT 1 FROM quiz_repair_answer WHERE room_id = ?)""",
        Boolean.class,
        roomId,
        roomId
      )
    );
  }

  public List<RoomStats> stats() {
    return db.query(STATS_SQL, (rs, row) -> {
      Map<QuizSection, Integer> counts = new EnumMap<>(QuizSection.class);
      for (QuizSection section : QuizSection.values()) counts.put(section, rs.getInt(section.name().toLowerCase(Locale.ROOT)));
      return new RoomStats(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getBoolean("has_clue_key"),
        counts,
        rs.getInt("repairs"),
        rs.getInt("question_points") + rs.getInt("repair_points")
      );
    });
  }

  // ---- questions ----------------------------------------------------------------------------------

  public long insertQuestion(long roomId, QuizSection section, String prompt, int points, String explanation, long by, Instant at) {
    return db.queryForObject(
      """
      INSERT INTO quiz_question(room_id, section, position, prompt, points, explanation, updated_by, updated_at)
      VALUES (?, ?, (SELECT COALESCE(max(position) + 1, 0) FROM quiz_question WHERE room_id = ?), ?, ?, ?, ?, ?)
      RETURNING id""",
      Long.class,
      roomId,
      section.name(),
      roomId,
      prompt,
      points,
      explanation,
      by,
      timestamp(at)
    );
  }

  public void updateQuestion(long questionId, QuizSection section, String prompt, int points, String explanation, long by, Instant at) {
    db.update(
      "UPDATE quiz_question SET section = ?, prompt = ?, points = ?, explanation = ?, updated_by = ?, updated_at = ? WHERE id = ?",
      section.name(),
      prompt,
      points,
      explanation,
      by,
      timestamp(at),
      questionId
    );
  }

  /** Replaces the question's options; rows go in in a random order (their ids carry no meaning). */
  public void replaceOptions(long questionId, List<NewOption> options) {
    db.update("DELETE FROM quiz_option WHERE question_id = ?", questionId);
    List<NewOption> shuffled = new ArrayList<>(options);
    Collections.shuffle(shuffled, random);
    db.batchUpdate(
      "INSERT INTO quiz_option(question_id, position, text, correct) VALUES (?, ?, ?, ?)",
      shuffled.stream().map(o -> new Object[] { questionId, o.position(), o.text(), o.correct() }).toList()
    );
  }

  /** Options go with the question (ON DELETE CASCADE). */
  public void deleteQuestion(long questionId) {
    db.update("DELETE FROM quiz_question WHERE id = ?", questionId);
  }

  // ---- repairs ------------------------------------------------------------------------------------

  public long insertRepair(long roomId, String title, String instructions, int points, long by, Instant at) {
    return db.queryForObject(
      """
      INSERT INTO quiz_repair(room_id, position, title, instructions, points, updated_by, updated_at)
      VALUES (?, (SELECT COALESCE(max(position) + 1, 0) FROM quiz_repair WHERE room_id = ?), ?, ?, ?, ?, ?)
      RETURNING id""",
      Long.class,
      roomId,
      roomId,
      title,
      instructions,
      points,
      by,
      timestamp(at)
    );
  }

  public void updateRepair(long repairId, String title, String instructions, int points, long by, Instant at) {
    db.update(
      "UPDATE quiz_repair SET title = ?, instructions = ?, points = ?, updated_by = ?, updated_at = ? WHERE id = ?",
      title,
      instructions,
      points,
      by,
      timestamp(at),
      repairId
    );
  }

  /** Replaces the repair's snippets ({@code codes} in their correct order); rows go in in a random order. */
  public void replaceSnippets(long repairId, List<String> codes) {
    db.update("DELETE FROM quiz_snippet WHERE repair_id = ?", repairId);
    List<Object[]> rows = new ArrayList<>();
    for (int i = 0; i < codes.size(); i++) rows.add(new Object[] { repairId, i, codes.get(i) });
    Collections.shuffle(rows, random);
    db.batchUpdate("INSERT INTO quiz_snippet(repair_id, correct_position, code) VALUES (?, ?, ?)", rows);
  }

  public void deleteRepair(long repairId) {
    db.update("DELETE FROM quiz_repair WHERE id = ?", repairId);
  }

  /** Import: removes every question and repair of the room (only called when no team has answered any). */
  public void deleteAll(long roomId) {
    db.update("DELETE FROM quiz_question WHERE room_id = ?", roomId);
    db.update("DELETE FROM quiz_repair WHERE room_id = ?", roomId);
  }

  // ---- mapping ------------------------------------------------------------------------------------

  private static List<QuizQuestionRecord> questionRows(ResultSet rs) throws SQLException {
    Map<Long, QuizQuestionRecord> questions = new LinkedHashMap<>();
    while (rs.next()) {
      long id = rs.getLong("id");
      QuizQuestionRecord question = questions.get(id);
      if (question == null) {
        question = new QuizQuestionRecord(
          id,
          rs.getLong("room_id"),
          QuizSection.valueOf(rs.getString("section")),
          rs.getInt("position"),
          rs.getString("prompt"),
          rs.getInt("points"),
          rs.getString("explanation"),
          instant(rs, "updated_at"),
          rs.getBoolean("answered"),
          new ArrayList<>()
        );
        questions.put(id, question);
      }
      long optionId = rs.getLong("option_id");
      if (!rs.wasNull()) {
        question.options().add(
          new QuizQuestionRecord.Option(optionId, rs.getInt("option_position"), rs.getString("option_text"), rs.getBoolean("correct"))
        );
      }
    }
    return questions.values().stream().map(q -> withOptions(q, List.copyOf(q.options()))).toList();
  }

  private static List<QuizRepairRecord> repairRows(ResultSet rs) throws SQLException {
    Map<Long, QuizRepairRecord> repairs = new LinkedHashMap<>();
    while (rs.next()) {
      long id = rs.getLong("id");
      QuizRepairRecord repair = repairs.get(id);
      if (repair == null) {
        repair = new QuizRepairRecord(
          id,
          rs.getLong("room_id"),
          rs.getInt("position"),
          rs.getString("title"),
          rs.getString("instructions"),
          rs.getInt("points"),
          instant(rs, "updated_at"),
          rs.getBoolean("answered"),
          new ArrayList<>()
        );
        repairs.put(id, repair);
      }
      long snippetId = rs.getLong("snippet_id");
      if (!rs.wasNull()) {
        repair.snippets().add(new QuizRepairRecord.Snippet(snippetId, rs.getInt("correct_position"), rs.getString("code")));
      }
    }
    return repairs.values().stream().map(r -> withSnippets(r, List.copyOf(r.snippets()))).toList();
  }

  private static QuizQuestionRecord withOptions(QuizQuestionRecord q, List<QuizQuestionRecord.Option> options) {
    return new QuizQuestionRecord(q.id(), q.roomId(), q.section(), q.position(), q.prompt(), q.points(), q.explanation(), q.updatedAt(), q.answered(), options);
  }

  private static QuizRepairRecord withSnippets(QuizRepairRecord r, List<QuizRepairRecord.Snippet> snippets) {
    return new QuizRepairRecord(r.id(), r.roomId(), r.position(), r.title(), r.instructions(), r.points(), r.updatedAt(), r.answered(), snippets);
  }
}
