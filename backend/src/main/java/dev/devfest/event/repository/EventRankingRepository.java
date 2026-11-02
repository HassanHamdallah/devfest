package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.nullableInt;
import static dev.devfest.event.repository.EventJdbc.nullableLong;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The one ranking definition (decision D12), used by the admin board, results and the export.
 * Finished teams: adjusted seconds, then active penalty entries, then finish time; exact ties share a rank.
 * Unfinished teams follow without a rank, by collected clues descending, then name. Disqualified teams come
 * last, never ranked and never counted in rank numbers. Time adjustments (PenaltyKind.TIME_ADJUSTMENT, signed
 * minutes) count in adjusted seconds but are not penalty entries (PenaltyKind.penaltyEntry()).
 */
@Repository
public class EventRankingRepository {

  private static final String RANKING_SQL = """
    WITH clues AS (
      SELECT cc.team_id, count(*) AS clues, bool_or(cc.source = 'RECOVERY') AS recovery_used,
             array_agg(r.code ORDER BY r.position) AS clue_rooms
      FROM collected_clue cc JOIN event_room r ON r.id = cc.room_id
      GROUP BY cc.team_id
    ), penalties AS (
      SELECT team_id, sum(minutes) AS minutes, count(*) FILTER (WHERE kind <> 'TIME_ADJUSTMENT') AS entries
      FROM penalty WHERE voided_at IS NULL GROUP BY team_id
    ), accusations AS (
      SELECT team_id, count(*) AS attempts FROM accusation GROUP BY team_id
    ), scored AS (
      SELECT t.id AS team_id, t.name AS team_name, t.group_code, t.disqualified,
             COALESCE(c.clues, 0) AS clues,
             COALESCE(c.clue_rooms, ARRAY[]::varchar[]) AS clue_rooms,
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
           CASE WHEN finished_at IS NOT NULL AND NOT disqualified THEN
             rank() OVER (PARTITION BY finished_at IS NULL OR disqualified
                          ORDER BY adjusted_seconds, penalty_entries, finished_at)
           END AS rank
    FROM scored
    ORDER BY disqualified, finished_at IS NULL, adjusted_seconds, penalty_entries, finished_at, clues DESC, team_name
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
      clueRooms(rs),
      rs.getInt("penalty_minutes"),
      rs.getInt("penalty_entries"),
      finishedAt != null,
      finishedAt,
      nullableLong(rs, "adjusted_seconds"),
      rs.getBoolean("recovery_used"),
      rs.getInt("accusation_attempts"),
      rs.getBoolean("disqualified")
    );
  }

  /** The rooms whose clue the team holds, in room position order (empty when none). */
  private static List<RoomCode> clueRooms(ResultSet rs) throws SQLException {
    Array array = rs.getArray("clue_rooms");
    try {
      return Arrays.stream((String[]) array.getArray()).map(RoomCode::valueOf).toList();
    } finally {
      array.free();
    }
  }
}
