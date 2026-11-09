package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.nullableLong;
import static dev.devfest.event.repository.EventJdbc.uuid;

import dev.devfest.event.domain.ClueOverride;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.JudgeStatus;
import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.domain.RoomCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Aggregated board reads: each method is ONE statement whatever the number of teams. */
@Repository
public class EventBoardRepository {

  private static final String TEAM_ROOMS_SQL = """
    SELECT r.id AS room_id, r.code, r.name, r.position, r.is_open, r.terminal_enabled, r.activity,
           COALESCE(j.status, 'PENDING') AS judgement,
           c.room_id IS NOT NULL AS clue_collected,
           CASE WHEN c.room_id IS NOT NULL THEN r.clue_key END AS collected_clue_key,
           ls.id AS last_id, ls.received_at AS last_received_at, ls.clue_correct AS last_clue_correct,
           ls.effective_correct AS last_effective_correct, ls.technical_output AS last_technical_output,
           ls.clue_answer AS last_clue_answer,
           (EXISTS(SELECT 1 FROM sentinel_evaluation e WHERE e.team_id = t.id AND e.room_id = r.id)
             OR EXISTS(SELECT 1 FROM terminal_form f WHERE f.team_id = t.id AND f.room_id = r.id AND f.submitted_at IS NOT NULL)
             OR EXISTS(SELECT 1 FROM contest_release c2 WHERE c2.team_id = t.id AND c2.room_id = r.id)) AS result_recorded
    FROM event_room r
    JOIN event_team t ON t.id = ?
    LEFT JOIN room_judgement j ON j.room_id = r.id AND j.team_id = t.id
    LEFT JOIN collected_clue c ON c.room_id = r.id AND c.team_id = t.id
    LEFT JOIN LATERAL (
      SELECT s.id, s.received_at, s.clue_correct, COALESCE(v.effective_correct, s.clue_correct) AS effective_correct,
             s.technical_output, s.clue_answer
      FROM room_submission s
      LEFT JOIN submission_clue_verdict v ON v.submission_id = s.id
      WHERE s.team_id = t.id AND s.room_id = r.id
      ORDER BY s.received_at DESC, s.id DESC LIMIT 1
    ) ls ON TRUE
    ORDER BY r.position
    """;

  private static final String ROOM_TEAMS_SQL = """
    SELECT t.id AS team_id, t.name AS team_name, t.group_code, t.disqualified, r.id AS room_id, r.code AS room_code,
           ls.id AS s_id, ls.round, ls.technical_output, ls.clue_answer, ls.clue_correct, ls.received_at,
           ls.idempotency_key, ls.request_hash, cnt.submissions,
           v.auto_correct, v.override, v.effective_correct, v.override_by, ou.name AS override_by_name,
           v.override_at, v.version AS verdict_version,
           j.status, j.note, j.version, j.reviewer_id, u.name AS reviewer_name, j.updated_at
    FROM event_team t
    JOIN event_room r ON r.id = ?
    LEFT JOIN LATERAL (
      SELECT s.* FROM room_submission s
      WHERE s.team_id = t.id AND s.room_id = r.id
      ORDER BY s.received_at DESC, s.id DESC LIMIT 1
    ) ls ON TRUE
    LEFT JOIN submission_clue_verdict v ON v.submission_id = ls.id
    LEFT JOIN app_user ou ON ou.id = v.override_by
    CROSS JOIN LATERAL (
      SELECT count(*) AS submissions FROM room_submission s WHERE s.team_id = t.id AND s.room_id = r.id
    ) cnt
    LEFT JOIN room_judgement j ON j.team_id = t.id AND j.room_id = r.id
    LEFT JOIN app_user u ON u.id = j.reviewer_id
    WHERE t.active
    ORDER BY t.name
    """;

  // "~ '\\S'" = contains a non-whitespace character, i.e. the field is really filled in.
  private static final String ADMIN_ROOMS_SQL = """
    SELECT r.id, r.code, r.name, r.position, r.is_open, r.manager_id, u.name AS manager_name,
           r.clue_key ~ '\\S' AS has_clue_key,
           r.terminal_enabled,
           (SELECT count(*) FROM room_terminal_file f WHERE f.room_id = r.id)
             + (SELECT count(*) FROM room_terminal_bundle b WHERE b.room_id = r.id) AS terminal_files,
           count(t.id) FILTER (WHERE j.status = 'PASS') AS passed,
           count(t.id) FILTER (WHERE j.status = 'FAIL') AS failed,
           count(t.id) FILTER (WHERE sub.submitted AND COALESCE(j.status, 'PENDING') = 'PENDING') AS pending,
           r.activity, EXISTS(SELECT 1 FROM contest_room_link l WHERE l.room_id = r.id) AS has_contest_link
    FROM event_room r
    LEFT JOIN app_user u ON u.id = r.manager_id
    LEFT JOIN event_team t ON t.active
    LEFT JOIN room_judgement j ON j.team_id = t.id AND j.room_id = r.id
    LEFT JOIN LATERAL (
      SELECT TRUE AS submitted FROM room_submission s WHERE s.team_id = t.id AND s.room_id = r.id LIMIT 1
    ) sub ON TRUE
    GROUP BY r.id, u.name
    ORDER BY r.position
    """;

  private static final String TEAM_FLAGS_SQL = """
    SELECT t.id, t.name, t.disqualified, er.code AS exception_room,
           (SELECT count(*) FROM collected_clue c WHERE c.team_id = t.id AND c.source = 'MANUAL') AS manual_clues
    FROM event_team t
    LEFT JOIN team_room_exception e ON e.team_id = t.id AND e.round = ?
    LEFT JOIN event_room er ON er.id = e.room_id
    WHERE t.active
    ORDER BY t.name
    """;

  private final JdbcTemplate db;

  public EventBoardRepository(JdbcTemplate db) {
    this.db = db;
  }

  /**
   * The four rooms in position order with the team's own judgement, clue and latest submission. The room's clue
   * key is selected only for rooms whose clue this team collected (CASE in SQL); the service still decides
   * whether it may be shown (EvidenceVisibility).
   */
  public List<TeamRoomProgressRecord> teamRooms(long teamId) {
    return db.query(TEAM_ROOMS_SQL, EventBoardRepository::mapTeamRoom, teamId);
  }

  /** Every active team (by name) with its latest submission, count and judgement in the room. */
  public List<RoomTeamProgressRecord> roomTeams(long roomId) {
    return db.query(ROOM_TEAMS_SQL, EventBoardRepository::mapRoomTeam, roomId);
  }

  public List<AdminRoomStatsRecord> adminRooms() {
    return db.query(ADMIN_ROOMS_SQL, EventBoardRepository::mapAdminRoom);
  }

  /** Every active team (by name) with its override markers for {@code round}. */
  public List<TeamFlagsRecord> teamFlags(int round) {
    return db.query(
      TEAM_FLAGS_SQL,
      (rs, row) ->
        new TeamFlagsRecord(
          rs.getLong("id"),
          rs.getString("name"),
          rs.getBoolean("disqualified"),
          rs.getString("exception_room") == null ? null : RoomCode.valueOf(rs.getString("exception_room")),
          rs.getInt("manual_clues")
        ),
      round
    );
  }

  private static TeamRoomProgressRecord mapTeamRoom(ResultSet rs, int row) throws SQLException {
    UUID lastId = uuid(rs, "last_id");
    return new TeamRoomProgressRecord(
      rs.getLong("room_id"),
      RoomCode.valueOf(rs.getString("code")),
      rs.getString("name"),
      rs.getInt("position"),
      rs.getBoolean("is_open"),
      rs.getBoolean("terminal_enabled"),
      RoomActivity.fromStored(rs.getString("activity")),
      JudgeStatus.valueOf(rs.getString("judgement")),
      rs.getBoolean("clue_collected"),
      rs.getString("collected_clue_key"),
      lastId,
      instant(rs, "last_received_at"),
      lastId == null ? null : rs.getBoolean("last_clue_correct"),
      lastId == null ? null : rs.getBoolean("last_effective_correct"),
      rs.getString("last_technical_output"),
      rs.getString("last_clue_answer"),
      rs.getBoolean("result_recorded")
    );
  }

  private static RoomTeamProgressRecord mapRoomTeam(ResultSet rs, int row) throws SQLException {
    long teamId = rs.getLong("team_id");
    RoomSubmissionRecord latest = latestSubmission(rs, teamId);
    return new RoomTeamProgressRecord(
      teamId,
      rs.getString("team_name"),
      GroupCode.valueOf(rs.getString("group_code")),
      rs.getBoolean("disqualified"),
      latest,
      latest == null ? null : latestVerdict(rs, latest),
      rs.getInt("submissions"),
      judgement(rs, teamId)
    );
  }

  private static RoomSubmissionRecord latestSubmission(ResultSet rs, long teamId) throws SQLException {
    UUID id = uuid(rs, "s_id");
    if (id == null) return null;
    return new RoomSubmissionRecord(
      id,
      teamId,
      rs.getLong("room_id"),
      RoomCode.valueOf(rs.getString("room_code")),
      rs.getInt("round"),
      rs.getString("technical_output"),
      rs.getString("clue_answer"),
      rs.getBoolean("clue_correct"),
      instant(rs, "received_at"),
      uuid(rs, "idempotency_key"),
      rs.getString("request_hash"),
      rs.getBoolean("effective_correct")
    );
  }

  private static ClueVerdictRecord latestVerdict(ResultSet rs, RoomSubmissionRecord latest) throws SQLException {
    return new ClueVerdictRecord(
      latest.id(),
      latest.teamId(),
      latest.roomId(),
      rs.getBoolean("auto_correct"),
      ClueOverride.fromStored(rs.getString("override")),
      rs.getBoolean("effective_correct"),
      nullableLong(rs, "override_by"),
      rs.getString("override_by_name"),
      instant(rs, "override_at"),
      rs.getLong("verdict_version")
    );
  }

  private static RoomJudgementRecord judgement(ResultSet rs, long teamId) throws SQLException {
    long roomId = rs.getLong("room_id");
    String status = rs.getString("status");
    if (status == null) return RoomJudgementRecord.pending(teamId, roomId);
    return new RoomJudgementRecord(
      teamId,
      roomId,
      JudgeStatus.valueOf(status),
      rs.getString("note"),
      rs.getLong("version"),
      nullableLong(rs, "reviewer_id"),
      rs.getString("reviewer_name"),
      instant(rs, "updated_at")
    );
  }

  private static AdminRoomStatsRecord mapAdminRoom(ResultSet rs, int row) throws SQLException {
    return new AdminRoomStatsRecord(
      rs.getLong("id"),
      RoomCode.valueOf(rs.getString("code")),
      rs.getString("name"),
      rs.getInt("position"),
      rs.getBoolean("is_open"),
      nullableLong(rs, "manager_id"),
      rs.getString("manager_name"),
      rs.getBoolean("has_clue_key"),
      rs.getBoolean("terminal_enabled"),
      rs.getInt("terminal_files"),
      rs.getInt("passed"),
      rs.getInt("failed"),
      rs.getInt("pending"),
      RoomActivity.fromStored(rs.getString("activity")),
      rs.getBoolean("has_contest_link")
    );
  }
}
