package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.nullableLong;
import static dev.devfest.event.repository.EventJdbc.timestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Snapshots: the rows a reset is about to delete (and the event state before it) as one JSONB document, built
 * by PostgreSQL in a single INSERT … SELECT inside the reset's transaction, so a failed snapshot changes nothing.
 * Never deleted by the application. Room secrets (clue keys) and accounts are not included.
 */
@Repository
public class EventSnapshotRepository {

  /** What a snapshot covers. TEAM filters every table by the team id parameter. */
  public enum Scope {
    FINAL,
    TEAM,
    EVENT,
  }

  private static final String EVENT_STATE = "SELECT * FROM event_state";
  private static final String ROOM_STATE = "SELECT id, code, is_open FROM event_room";
  private static final String TEAM_SUBMISSIONS = "SELECT id FROM room_submission WHERE team_id = ?";

  private final JdbcTemplate db;

  public EventSnapshotRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** Returns the new snapshot id; {@code teamId} is required for TEAM and null otherwise. */
  public long create(Scope scope, String kind, String reason, Long teamId, long createdBy, Instant at) {
    Map<String, String> tables = tables(scope);
    List<Object> args = new ArrayList<>(List.of(timestamp(at), createdBy, kind, reason));
    args.add(teamId);
    StringBuilder data = new StringBuilder("jsonb_build_object(");
    String separator = "";
    for (Map.Entry<String, String> table : tables.entrySet()) {
      data.append(separator)
        .append('\'').append(table.getKey()).append("', ")
        .append("(SELECT COALESCE(jsonb_agg(to_jsonb(x)), '[]'::jsonb) FROM (").append(table.getValue()).append(") x)");
      separator = ", ";
      long parameters = table.getValue().chars().filter(c -> c == '?').count();
      for (int i = 0; i < parameters; i++) args.add(teamId);
    }
    data.append(')');
    return db.queryForObject(
      "INSERT INTO event_snapshot(created_at, created_by, kind, reason, team_id, data) SELECT ?, ?, ?, ?, ?, " +
      data +
      " RETURNING id",
      Long.class,
      args.toArray()
    );
  }

  /** Newest first, without the data. */
  public List<SnapshotRecord> list() {
    return db.query(
      """
      SELECT s.id, s.created_at, u.name AS created_by_name, s.kind, s.reason, s.team_id, t.name AS team_name
      FROM event_snapshot s
      JOIN app_user u ON u.id = s.created_by
      LEFT JOIN event_team t ON t.id = s.team_id
      ORDER BY s.id DESC""",
      (rs, row) ->
        new SnapshotRecord(
          rs.getLong("id"),
          instant(rs, "created_at"),
          rs.getString("created_by_name"),
          rs.getString("kind"),
          rs.getString("reason"),
          nullableLong(rs, "team_id"),
          rs.getString("team_name")
        )
    );
  }

  /** The whole snapshot (metadata and data) as a JSON document, for download. */
  public Optional<String> document(long id) {
    return db
      .queryForList(
        """
        SELECT jsonb_build_object('id', s.id, 'createdAt', s.created_at, 'createdBy', u.name, 'kind', s.kind,
                                  'reason', s.reason, 'teamId', s.team_id, 'data', s.data)::text
        FROM event_snapshot s JOIN app_user u ON u.id = s.created_by WHERE s.id = ?""",
        String.class,
        id
      )
      .stream()
      .findFirst();
  }

  /** Table name → SELECT of the rows to copy, matching EventResetRepository's deletes for the same scope. */
  private static Map<String, String> tables(Scope scope) {
    Map<String, String> tables = new LinkedHashMap<>();
    tables.put("event_state", EVENT_STATE);
    switch (scope) {
      case FINAL -> {
        for (String table : List.of(
          "team_final", "final_unlock_attempt", "final_clue_attempt", "recovery_check", "team_recovery", "final_time_adjustment"
        )) {
          tables.put(table, "SELECT * FROM " + table);
        }
        tables.put("collected_clue", "SELECT * FROM collected_clue WHERE source = 'RECOVERY'");
      }
      case TEAM -> {
        for (String table : List.of(
          "team_final", "final_unlock_attempt", "final_clue_attempt", "recovery_check", "team_recovery",
          "collected_clue", "room_submission", "room_judgement_history", "room_judgement", "score_deduction",
          "room_manual_score", "room_points_entry", "team_room_exception", "sentinel_run",
          "sentinel_submission", "sentinel_evaluation", "terminal_form", "terminal_form_request",
          "terminal_form_override", "contest_result", "contest_release"
        )) {
          tables.put(table, "SELECT * FROM " + table + " WHERE team_id = ?");
        }
        tables.put("submission_clue_verdict",
          "SELECT * FROM submission_clue_verdict WHERE submission_id IN (" + TEAM_SUBMISSIONS + ")");
        tables.put("submission_clue_verdict_history",
          "SELECT * FROM submission_clue_verdict_history WHERE submission_id IN (" + TEAM_SUBMISSIONS + ")");
      }
      case EVENT -> {
        for (String table : List.of(
          "team_final", "final_unlock_attempt", "final_clue_attempt", "recovery_check", "team_recovery",
          "final_time_adjustment", "collected_clue", "room_submission", "submission_clue_verdict",
          "submission_clue_verdict_history", "room_judgement_history", "room_judgement", "score_deduction",
          "room_manual_score", "room_points_entry", "team_room_exception", "room_arrival", "round_time_adjustment",
          "sentinel_run", "sentinel_submission", "sentinel_evaluation", "terminal_form",
          "terminal_form_request", "terminal_form_override", "contest_result", "contest_release"
        )) {
          tables.put(table, "SELECT * FROM " + table);
        }
        tables.put("event_room", ROOM_STATE);
      }
    }
    return tables;
  }
}
