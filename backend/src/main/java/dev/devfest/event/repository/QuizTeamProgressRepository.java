package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Per team and AI room: when the team first acted and when it finished its MCQs. */
@Repository
public class QuizTeamProgressRepository {

  private static final String COLUMNS = "SELECT team_id, room_id, created_at, mcq_finished_at FROM quiz_team_progress ";

  private final JdbcTemplate db;

  public QuizTeamProgressRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Optional<QuizTeamProgressRecord> find(long teamId, long roomId) {
    return db.query(COLUMNS + "WHERE team_id = ? AND room_id = ?", QuizTeamProgressRepository::map, teamId, roomId).stream().findFirst();
  }

  public List<QuizTeamProgressRecord> forRoom(long roomId) {
    return db.query(COLUMNS + "WHERE room_id = ?", QuizTeamProgressRepository::map, roomId);
  }

  /** Records the team's first action in the room; later calls keep the first time. */
  public void createIfAbsent(long teamId, long roomId, Instant at) {
    db.update(
      "INSERT INTO quiz_team_progress(team_id, room_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
      teamId,
      roomId,
      timestamp(at)
    );
  }

  /** "Finish MCQs" is irreversible: only the first call sets the time. */
  public void finishMcq(long teamId, long roomId, Instant at) {
    db.update(
      "UPDATE quiz_team_progress SET mcq_finished_at = ? WHERE team_id = ? AND room_id = ? AND mcq_finished_at IS NULL",
      timestamp(at),
      teamId,
      roomId
    );
  }

  private static QuizTeamProgressRecord map(ResultSet rs, int row) throws SQLException {
    return new QuizTeamProgressRecord(
      rs.getLong("team_id"),
      rs.getLong("room_id"),
      instant(rs, "created_at"),
      instant(rs, "mcq_finished_at")
    );
  }
}
