package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;
import static dev.devfest.event.repository.EventJdbc.uuid;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.TerminalFormCause;
import dev.devfest.event.domain.TerminalFormValues;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The teams' forms of LINUX_TERMINAL rooms and the Idempotency-Keys of their Save and Final submit requests. */
@Repository
public class TerminalFormRepository {

  /** One active team with its form in the room (null when the team never saved anything there). */
  public record DashboardRow(long teamId, String teamName, GroupCode group, boolean disqualified, TerminalFormRecord form) {}

  /** A stored request: its kind ("SAVE" or "SUBMIT") and the hash that identifies its exact content. */
  public record StoredRequest(String kind, String requestHash) {}

  private static final String SELECT = """
    SELECT team_id, room_id, path_value, username_value, file_size_value, pattern_value, clue_value,
           saved_at, submitted_at, submission_id, cause
    FROM terminal_form
    """;

  private final JdbcTemplate db;

  public TerminalFormRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Optional<TerminalFormRecord> find(long teamId, long roomId) {
    return db.query(SELECT + "WHERE team_id = ? AND room_id = ?", TerminalFormRepository::map, teamId, roomId).stream().findFirst();
  }

  /** Every form of the room that is not submitted yet, by team id (the round's end submits those with content). */
  public List<TerminalFormRecord> unsubmitted(long roomId) {
    return db.query(SELECT + "WHERE room_id = ? AND submitted_at IS NULL ORDER BY team_id", TerminalFormRepository::map, roomId);
  }

  /** Stores the values of a Save. A submitted form is never changed (the guard makes that a no-op, count 0). */
  public int save(long teamId, long roomId, TerminalFormValues values, Instant at) {
    return db.update(
      """
      INSERT INTO terminal_form(team_id, room_id, path_value, username_value, file_size_value, pattern_value,
                                clue_value, saved_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (team_id, room_id) DO UPDATE SET path_value = EXCLUDED.path_value,
        username_value = EXCLUDED.username_value, file_size_value = EXCLUDED.file_size_value,
        pattern_value = EXCLUDED.pattern_value,
        clue_value = EXCLUDED.clue_value, saved_at = EXCLUDED.saved_at
      WHERE terminal_form.submitted_at IS NULL""",
      teamId,
      roomId,
      values.path(),
      values.username(),
      values.fileSize(),
      values.pattern(),
      values.finalClue(),
      timestamp(at)
    );
  }

  /**
   * Locks the form with its final values and the submission it was recorded as. Returns 0 (and writes nothing)
   * when the form was submitted already, which is what makes a second trigger harmless.
   */
  public int submit(long teamId, long roomId, TerminalFormValues values, UUID submissionId, TerminalFormCause cause, Instant at) {
    return db.update(
      """
      INSERT INTO terminal_form(team_id, room_id, path_value, username_value, file_size_value, pattern_value,
                                clue_value, saved_at, submitted_at, submission_id, cause)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (team_id, room_id) DO UPDATE SET path_value = EXCLUDED.path_value,
        username_value = EXCLUDED.username_value, file_size_value = EXCLUDED.file_size_value,
        pattern_value = EXCLUDED.pattern_value,
        clue_value = EXCLUDED.clue_value, saved_at = EXCLUDED.saved_at, submitted_at = EXCLUDED.submitted_at,
        submission_id = EXCLUDED.submission_id, cause = EXCLUDED.cause
      WHERE terminal_form.submitted_at IS NULL""",
      teamId,
      roomId,
      values.path(),
      values.username(),
      values.fileSize(),
      values.pattern(),
      values.finalClue(),
      timestamp(at),
      timestamp(at),
      submissionId,
      cause.name()
    );
  }

  /** Every active team with its form in the room, in one statement. */
  public List<DashboardRow> dashboardRows(long roomId) {
    return db.query(
      """
      SELECT t.id AS team_id, t.name AS team_name, t.group_code, t.disqualified, f.room_id AS form_room_id,
             f.path_value, f.username_value, f.file_size_value, f.pattern_value, f.clue_value,
             f.saved_at, f.submitted_at, f.submission_id, f.cause
      FROM event_team t
      LEFT JOIN terminal_form f ON f.team_id = t.id AND f.room_id = ?
      WHERE t.active ORDER BY t.name""",
      (rs, row) -> {
        rs.getLong("form_room_id");
        TerminalFormRecord form = rs.wasNull()
          ? null
          : new TerminalFormRecord(
            rs.getLong("team_id"),
            roomId,
            new TerminalFormValues(
              rs.getString("path_value"),
              rs.getString("username_value"),
              rs.getString("file_size_value"),
              rs.getString("pattern_value"),
              rs.getString("clue_value")
            ),
            instant(rs, "saved_at"),
            instant(rs, "submitted_at"),
            uuid(rs, "submission_id"),
            rs.getString("cause") == null ? null : TerminalFormCause.valueOf(rs.getString("cause"))
          );
        return new DashboardRow(
          rs.getLong("team_id"),
          rs.getString("team_name"),
          GroupCode.valueOf(rs.getString("group_code")),
          rs.getBoolean("disqualified"),
          form
        );
      },
      roomId
    );
  }

  public Optional<StoredRequest> findRequest(long teamId, UUID idempotencyKey) {
    return db
      .query(
        "SELECT kind, request_hash FROM terminal_form_request WHERE team_id = ? AND idempotency_key = ?",
        (rs, row) -> new StoredRequest(rs.getString("kind"), rs.getString("request_hash")),
        teamId,
        idempotencyKey
      )
      .stream()
      .findFirst();
  }

  public void insertRequest(long teamId, UUID idempotencyKey, long roomId, String kind, String requestHash, Instant at) {
    db.update(
      "INSERT INTO terminal_form_request(team_id, idempotency_key, room_id, kind, request_hash, created_at) VALUES (?, ?, ?, ?, ?, ?)",
      teamId,
      idempotencyKey,
      roomId,
      kind,
      requestHash,
      timestamp(at)
    );
  }

  private static TerminalFormRecord map(ResultSet rs, int row) throws SQLException {
    String cause = rs.getString("cause");
    return new TerminalFormRecord(
      rs.getLong("team_id"),
      rs.getLong("room_id"),
      new TerminalFormValues(
        rs.getString("path_value"),
        rs.getString("username_value"),
        rs.getString("file_size_value"),
        rs.getString("pattern_value"),
        rs.getString("clue_value")
      ),
      instant(rs, "saved_at"),
      instant(rs, "submitted_at"),
      uuid(rs, "submission_id"),
      cause == null ? null : TerminalFormCause.valueOf(cause)
    );
  }
}
