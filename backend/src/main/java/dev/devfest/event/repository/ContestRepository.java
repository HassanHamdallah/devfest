package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;

import dev.devfest.event.domain.ContestProblem;
import dev.devfest.event.domain.GroupCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The Problem Solving room's data: the room's contest link, the typed results and the clue releases. Totals are never
 * stored; the release rows are append-only (the table rejects UPDATE and DELETE).
 */
@Repository
public class ContestRepository {

  /** Active, not disqualified teams with their saved result and release, by group and name. Parameters: room, room. */
  private static final String ROWS_SQL = """
    SELECT t.id, t.name, t.group_code, r.team_id IS NOT NULL AS saved,
           COALESCE(r.solved_a, FALSE) AS solved_a, COALESCE(r.solved_b, FALSE) AS solved_b,
           COALESCE(r.solved_c, FALSE) AS solved_c, COALESCE(r.solved_d, FALSE) AS solved_d,
           COALESCE(r.solved_e, FALSE) AS solved_e, COALESCE(r.penalty_minutes, 0) AS penalty_minutes,
           r.saved_at, rel.released_at
    FROM event_team t
    LEFT JOIN contest_result r ON r.team_id = t.id AND r.room_id = ?
    LEFT JOIN contest_release rel ON rel.team_id = t.id AND rel.room_id = ?
    WHERE t.active AND NOT t.disqualified
    ORDER BY t.group_code, t.name
    """;

  private final JdbcTemplate db;

  public ContestRepository(JdbcTemplate db) {
    this.db = db;
  }

  // ---- the link --------------------------------------------------------------------------------------

  public Optional<String> link(long roomId) {
    return db
      .queryForList("SELECT contest_url FROM contest_room_link WHERE room_id = ?", String.class, roomId)
      .stream()
      .findFirst();
  }

  public void saveLink(long roomId, String url, long updatedBy, Instant at) {
    db.update(
      """
      INSERT INTO contest_room_link(room_id, contest_url, updated_by, updated_at) VALUES (?, ?, ?, ?)
      ON CONFLICT (room_id) DO UPDATE SET contest_url = EXCLUDED.contest_url, updated_by = EXCLUDED.updated_by,
        updated_at = EXCLUDED.updated_at""",
      roomId,
      url,
      updatedBy,
      timestamp(at)
    );
  }

  /** Returns true when a link was removed. */
  public boolean clearLink(long roomId) {
    return db.update("DELETE FROM contest_room_link WHERE room_id = ?", roomId) == 1;
  }

  // ---- results ---------------------------------------------------------------------------------------

  /** Every eligible-to-list team of the room, one statement whatever the number of teams. */
  public List<ContestTeamRecord> teams(long roomId) {
    return db.query(ROWS_SQL, ContestRepository::mapTeam, roomId, roomId);
  }

  public void saveResult(long teamId, long roomId, Set<ContestProblem> solved, int penaltyMinutes, long savedBy, Instant at) {
    db.update(
      """
      INSERT INTO contest_result(team_id, room_id, solved_a, solved_b, solved_c, solved_d, solved_e,
                                 penalty_minutes, saved_by, saved_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (team_id, room_id) DO UPDATE SET solved_a = EXCLUDED.solved_a, solved_b = EXCLUDED.solved_b,
        solved_c = EXCLUDED.solved_c, solved_d = EXCLUDED.solved_d, solved_e = EXCLUDED.solved_e,
        penalty_minutes = EXCLUDED.penalty_minutes, saved_by = EXCLUDED.saved_by, saved_at = EXCLUDED.saved_at""",
      teamId,
      roomId,
      solved.contains(ContestProblem.A),
      solved.contains(ContestProblem.B),
      solved.contains(ContestProblem.C),
      solved.contains(ContestProblem.D),
      solved.contains(ContestProblem.E),
      penaltyMinutes,
      savedBy,
      timestamp(at)
    );
  }

  // ---- releases --------------------------------------------------------------------------------------

  public boolean released(long teamId, long roomId) {
    return Boolean.TRUE.equals(
      db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM contest_release WHERE team_id = ? AND room_id = ?)",
        Boolean.class,
        teamId,
        roomId
      )
    );
  }

  /** The primary key (team, room) refuses a second release, however two requests race. */
  public void insertRelease(long teamId, long roomId, int round, UUID submissionId, long releasedBy, Instant at) {
    db.update(
      """
      INSERT INTO contest_release(team_id, room_id, round, submission_id, released_by, released_at)
      VALUES (?, ?, ?, ?, ?, ?)""",
      teamId,
      roomId,
      round,
      submissionId,
      releasedBy,
      timestamp(at)
    );
  }

  private static ContestTeamRecord mapTeam(ResultSet rs, int row) throws SQLException {
    Set<ContestProblem> solved = EnumSet.noneOf(ContestProblem.class);
    if (rs.getBoolean("solved_a")) solved.add(ContestProblem.A);
    if (rs.getBoolean("solved_b")) solved.add(ContestProblem.B);
    if (rs.getBoolean("solved_c")) solved.add(ContestProblem.C);
    if (rs.getBoolean("solved_d")) solved.add(ContestProblem.D);
    if (rs.getBoolean("solved_e")) solved.add(ContestProblem.E);
    return new ContestTeamRecord(
      rs.getLong("id"),
      rs.getString("name"),
      GroupCode.valueOf(rs.getString("group_code")),
      rs.getBoolean("saved"),
      solved,
      rs.getInt("penalty_minutes"),
      instant(rs, "saved_at"),
      instant(rs, "released_at")
    );
  }
}
