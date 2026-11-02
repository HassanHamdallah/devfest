package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.timestamp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Clues a team holds, at most one per room; the first collection wins and is never removed. */
@Repository
public class CollectedClueRepository {

  private final JdbcTemplate db;

  public CollectedClueRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** Returns true when this correct submission is the team's first clue for the room. */
  public boolean collectFromRoom(long teamId, long roomId, UUID submissionId, Instant collectedAt) {
    return insertIfAbsent(teamId, roomId, "ROOM", submissionId, collectedAt);
  }

  /** Returns true when the recovery filled the team's missing room. */
  public boolean collectFromRecovery(long teamId, long roomId, Instant collectedAt) {
    return insertIfAbsent(teamId, roomId, "RECOVERY", null, collectedAt);
  }

  public int countForTeam(long teamId) {
    return db.queryForObject("SELECT count(*) FROM collected_clue WHERE team_id = ?", Integer.class, teamId);
  }

  /** Room ids ordered by room position. */
  public List<Long> roomIdsForTeam(long teamId) {
    return db.queryForList(
      """
      SELECT c.room_id FROM collected_clue c JOIN event_room r ON r.id = c.room_id
      WHERE c.team_id = ? ORDER BY r.position""",
      Long.class,
      teamId
    );
  }

  public boolean usedRecovery(long teamId) {
    return db.queryForObject(
      "SELECT EXISTS(SELECT 1 FROM collected_clue WHERE team_id = ? AND source = 'RECOVERY')",
      Boolean.class,
      teamId
    );
  }

  private boolean insertIfAbsent(long teamId, long roomId, String source, UUID submissionId, Instant at) {
    return db.update(
      """
      INSERT INTO collected_clue(team_id, room_id, source, submission_id, collected_at)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (team_id, room_id) DO NOTHING""",
      teamId,
      roomId,
      source,
      submissionId,
      timestamp(at)
    ) == 1;
  }
}
