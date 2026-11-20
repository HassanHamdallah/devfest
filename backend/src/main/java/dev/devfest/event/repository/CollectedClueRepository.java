package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.timestamp;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Clues a team holds, at most one per room. A ROOM clue follows the effective clue verdicts of the team's
 * submissions in that room (it can be removed when a key change or override makes them all incorrect);
 * a RECOVERY clue is never removed.
 */
@Repository
public class CollectedClueRepository {

  private final JdbcTemplate db;

  public CollectedClueRepository(JdbcTemplate db) {
    this.db = db;
  }

  /**
   * Makes the room's ROOM clues match the effective verdicts, for every team ({@code teamId} null) or one
   * team: drops ROOM clues whose submission is no longer correct, then collects the earliest correct
   * submission of each team that holds no clue for the room. RECOVERY clues are left alone. Two statements.
   */
  public void recomputeRoomClues(long roomId, Long teamId) {
    db.update(
      """
      DELETE FROM collected_clue c
      USING submission_clue_verdict v
      WHERE c.room_id = ? AND c.source = 'ROOM' AND (?::bigint IS NULL OR c.team_id = ?)
        AND v.submission_id = c.submission_id AND NOT v.effective_correct""",
      roomId,
      teamId,
      teamId
    );
    db.update(
      """
      INSERT INTO collected_clue(team_id, room_id, source, submission_id, collected_at)
      SELECT DISTINCT ON (s.team_id) s.team_id, s.room_id, 'ROOM', s.id, s.received_at
      FROM room_submission s
      JOIN submission_clue_verdict v ON v.submission_id = s.id
      WHERE s.room_id = ? AND (?::bigint IS NULL OR s.team_id = ?) AND v.effective_correct
      ORDER BY s.team_id, s.received_at, s.id
      ON CONFLICT (team_id, room_id) DO NOTHING""",
      roomId,
      teamId,
      teamId
    );
  }

  /** Returns true when this correct submission is the team's first clue for the room. */
  public boolean collectFromRoom(long teamId, long roomId, UUID submissionId, Instant collectedAt) {
    return insertIfAbsent(teamId, roomId, "ROOM", submissionId, collectedAt);
  }

  /** Returns true when the admin's grant gave the team a clue it did not hold (an existing clue is kept). */
  public boolean grantManual(long teamId, long roomId, Instant collectedAt) {
    return insertIfAbsent(teamId, roomId, "MANUAL", null, collectedAt);
  }

  /** Removes the team's ROOM or MANUAL clue for the room, and a RECOVERY clue only if asked; returns rows removed. */
  public int revoke(long teamId, long roomId, boolean includeRecovery) {
    return db.update(
      """
      DELETE FROM collected_clue
      WHERE team_id = ? AND room_id = ? AND (source IN ('ROOM', 'MANUAL') OR (? AND source = 'RECOVERY'))""",
      teamId,
      roomId,
      includeRecovery
    );
  }

  /** The source of the team's clue for the room (ROOM, RECOVERY, MANUAL), if it holds one. */
  public Optional<String> source(long teamId, long roomId) {
    return db
      .queryForList("SELECT source FROM collected_clue WHERE team_id = ? AND room_id = ?", String.class, teamId, roomId)
      .stream()
      .findFirst();
  }

  /**
   * Grants the team every room key it does not hold (source RECOVERY, never removed by a recheck); a key it holds is
   * kept. Returns how many were granted; a second call grants nothing.
   */
  public int grantMissingFromRecovery(long teamId, Instant collectedAt) {
    return db.update(
      """
      INSERT INTO collected_clue(team_id, room_id, source, submission_id, collected_at)
      SELECT ?, r.id, 'RECOVERY', NULL, ? FROM event_room r
      ON CONFLICT (team_id, room_id) DO NOTHING""",
      teamId,
      timestamp(collectedAt)
    );
  }

  /** A room key the team got from its recovery, with the room's name (for that team's unlock screen only). */
  public record RecoveredKey(String roomName, String clueKey) {}

  /** The team's RECOVERY keys in room order, as ONE statement. */
  public List<RecoveredKey> recoveredKeys(long teamId) {
    return db.query(
      """
      SELECT r.name, r.clue_key FROM collected_clue c JOIN event_room r ON r.id = c.room_id
      WHERE c.team_id = ? AND c.source = 'RECOVERY' ORDER BY r.position""",
      (rs, row) -> new RecoveredKey(rs.getString("name"), rs.getString("clue_key")),
      teamId
    );
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
