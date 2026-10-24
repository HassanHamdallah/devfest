package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.nullableLong;
import static dev.devfest.event.repository.EventJdbc.timestamp;
import static dev.devfest.event.repository.EventJdbc.uuid;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Finish records: one per team, written by its correct accusation. */
@Repository
public class TeamFinalRepository {

  private static final String SUMMARY_SQL = """
    SELECT (SELECT count(*) FROM collected_clue c WHERE c.team_id = t.id) AS clues,
           (SELECT r.id FROM event_room r
            WHERE NOT EXISTS (SELECT 1 FROM collected_clue c WHERE c.team_id = t.id AND c.room_id = r.id)
            ORDER BY r.position LIMIT 1) AS missing_room_id,
           (SELECT count(*) FROM recovery_attempt ra WHERE ra.team_id = t.id) AS recovery_attempts,
           (SELECT count(*) FROM accusation a WHERE a.team_id = t.id) AS accusation_attempts,
           (SELECT count(*) FROM accusation a WHERE a.team_id = t.id AND NOT a.correct) AS wrong_accusations,
           (SELECT COALESCE(sum(p.minutes), 0) FROM penalty p
            WHERE p.team_id = t.id AND p.voided_at IS NULL) AS penalty_minutes,
           t.id AS team_id, f.finished_at, f.finishing_accusation_id, s.name AS suspect_name
    FROM event_team t
    LEFT JOIN team_final f ON f.team_id = t.id
    LEFT JOIN accusation fa ON fa.id = f.finishing_accusation_id
    LEFT JOIN suspect s ON s.id = fa.suspect_id
    WHERE t.id = ?
    """;

  private final JdbcTemplate db;

  public TeamFinalRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Optional<TeamFinishRecord> findForTeam(long teamId) {
    return db.query(
      """
      SELECT f.team_id, f.finished_at, f.finishing_accusation_id, s.name AS suspect_name
      FROM team_final f
      JOIN accusation a ON a.id = f.finishing_accusation_id
      JOIN suspect s ON s.id = a.suspect_id
      WHERE f.team_id = ?""",
      (rs, row) ->
        new TeamFinishRecord(
          rs.getLong("team_id"),
          instant(rs, "finished_at"),
          uuid(rs, "finishing_accusation_id"),
          rs.getString("suspect_name")
        ),
      teamId
    ).stream().findFirst();
  }

  /** Everything the team's final screen and final writes count, as ONE statement. */
  public TeamFinalSummaryRecord summary(long teamId) {
    return db.queryForObject(SUMMARY_SQL, TeamFinalRepository::mapSummary, teamId);
  }

  /** Returns false when the team had already finished. */
  public boolean insert(long teamId, Instant finishedAt, UUID finishingAccusationId) {
    return db.update(
      """
      INSERT INTO team_final(team_id, finished_at, finishing_accusation_id) VALUES (?, ?, ?)
      ON CONFLICT (team_id) DO NOTHING""",
      teamId,
      timestamp(finishedAt),
      finishingAccusationId
    ) == 1;
  }

  private static TeamFinalSummaryRecord mapSummary(ResultSet rs, int row) throws SQLException {
    UUID accusationId = uuid(rs, "finishing_accusation_id");
    TeamFinishRecord finish = accusationId == null
      ? null
      : new TeamFinishRecord(rs.getLong("team_id"), instant(rs, "finished_at"), accusationId, rs.getString("suspect_name"));
    return new TeamFinalSummaryRecord(
      rs.getInt("clues"),
      nullableLong(rs, "missing_room_id"),
      rs.getInt("recovery_attempts"),
      rs.getInt("accusation_attempts"),
      rs.getInt("wrong_accusations"),
      rs.getInt("penalty_minutes"),
      finish
    );
  }
}
