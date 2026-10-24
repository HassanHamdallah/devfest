package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.nullableInt;
import static dev.devfest.event.repository.EventJdbc.nullableLong;

import dev.devfest.event.domain.GroupCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The one ranking definition (decision D12), used by the admin board, results and the export.
 * Finished teams: adjusted seconds, then active penalty entries, then finish time; exact ties share a rank.
 * Unfinished teams follow without a rank, by collected clues descending, then name.
 */
@Repository
public class EventRankingRepository {

  private static final String RANKING_SQL = """
    WITH clues AS (
      SELECT team_id, count(*) AS clues, bool_or(source = 'RECOVERY') AS recovery_used
      FROM collected_clue GROUP BY team_id
    ), penalties AS (
      SELECT team_id, sum(minutes) AS minutes, count(*) AS entries
      FROM penalty WHERE voided_at IS NULL GROUP BY team_id
    ), accusations AS (
      SELECT team_id, count(*) AS attempts FROM accusation GROUP BY team_id
    ), scored AS (
      SELECT t.id AS team_id, t.name AS team_name, t.group_code,
             COALESCE(c.clues, 0) AS clues,
             COALESCE(c.recovery_used, FALSE) AS recovery_used,
             COALESCE(p.minutes, 0) AS penalty_minutes,
             COALESCE(p.entries, 0) AS penalty_entries,
             COALESCE(a.attempts, 0) AS accusation_attempts,
             f.finished_at,
             floor(extract(epoch FROM f.finished_at - s.final_started_at))::bigint
               + 60 * COALESCE(p.minutes, 0) AS adjusted_seconds
      FROM event_team t
      JOIN event_state s ON s.id = 1
      LEFT JOIN clues c ON c.team_id = t.id
      LEFT JOIN penalties p ON p.team_id = t.id
      LEFT JOIN accusations a ON a.team_id = t.id
      LEFT JOIN team_final f ON f.team_id = t.id
      WHERE t.active
    )
    SELECT scored.*,
           CASE WHEN finished_at IS NOT NULL THEN
             rank() OVER (PARTITION BY finished_at IS NULL
                          ORDER BY adjusted_seconds, penalty_entries, finished_at)
           END AS rank
    FROM scored
    ORDER BY finished_at IS NULL, adjusted_seconds, penalty_entries, finished_at, clues DESC, team_name
    """;

  private final JdbcTemplate db;

  public EventRankingRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<RankedTeamRecord> ranking() {
    return db.query(RANKING_SQL, EventRankingRepository::map);
  }

  private static RankedTeamRecord map(ResultSet rs, int row) throws SQLException {
    var finishedAt = instant(rs, "finished_at");
    return new RankedTeamRecord(
      nullableInt(rs, "rank"),
      rs.getLong("team_id"),
      rs.getString("team_name"),
      GroupCode.valueOf(rs.getString("group_code")),
      rs.getInt("clues"),
      rs.getInt("penalty_minutes"),
      rs.getInt("penalty_entries"),
      finishedAt != null,
      finishedAt,
      nullableLong(rs, "adjusted_seconds"),
      rs.getBoolean("recovery_used"),
      rs.getInt("accusation_attempts")
    );
  }
}
