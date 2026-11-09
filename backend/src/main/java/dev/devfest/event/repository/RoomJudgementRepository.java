package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.nullableLong;
import static dev.devfest.event.repository.EventJdbc.timestamp;

import dev.devfest.event.domain.JudgeStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Judgements per team and room, each change mirrored by exactly one history row. */
@Repository
public class RoomJudgementRepository {

  private final JdbcTemplate db;

  public RoomJudgementRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** Never empty: an absent row is reported as {@link RoomJudgementRecord#pending}. */
  public RoomJudgementRecord find(long teamId, long roomId) {
    return db.query(
      """
      SELECT j.team_id, j.room_id, j.status, j.note, j.version, j.reviewer_id,
             u.name AS reviewer_name, j.updated_at
      FROM room_judgement j
      JOIN app_user u ON u.id = j.reviewer_id
      WHERE j.team_id = ? AND j.room_id = ?""",
      RoomJudgementRepository::map,
      teamId,
      roomId
    ).stream().findFirst().orElseGet(() -> RoomJudgementRecord.pending(teamId, roomId));
  }

  /**
   * Writes {@code previous.version() + 1} and its history row. Callers hold the event_state lock and
   * have already checked the client's version against {@code previous}.
   */
  public void saveWithHistory(
    RoomJudgementRecord previous,
    JudgeStatus status,
    String note,
    long reviewerId,
    Instant changedAt
  ) {
    long version = previous.version() + 1;
    upsert(previous.teamId(), previous.roomId(), status, note, reviewerId, changedAt, version);
    db.update(
      """
      INSERT INTO room_judgement_history(team_id, room_id, old_status, new_status, note,
                                         reviewer_id, changed_at, version)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
      previous.teamId(),
      previous.roomId(),
      previous.exists() ? previous.status().name() : null,
      status.name(),
      note,
      reviewerId,
      timestamp(changedAt),
      version
    );
  }

  /** Oldest first. */
  public List<JudgementHistoryRecord> history(long teamId, long roomId) {
    return db.query(
      """
      SELECT h.id, h.team_id, h.room_id, h.old_status, h.new_status, h.note, h.reviewer_id,
             u.name AS reviewer_name, h.changed_at, h.version
      FROM room_judgement_history h
      JOIN app_user u ON u.id = h.reviewer_id
      WHERE h.team_id = ? AND h.room_id = ?
      ORDER BY h.version""",
      RoomJudgementRepository::mapHistory,
      teamId,
      roomId
    );
  }

  private void upsert(
    long teamId,
    long roomId,
    JudgeStatus status,
    String note,
    long reviewerId,
    Instant updatedAt,
    long version
  ) {
    db.update(
      """
      INSERT INTO room_judgement(team_id, room_id, status, note, reviewer_id, updated_at, version)
      VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (team_id, room_id) DO UPDATE
      SET status = EXCLUDED.status, note = EXCLUDED.note,
          reviewer_id = EXCLUDED.reviewer_id, updated_at = EXCLUDED.updated_at, version = EXCLUDED.version""",
      teamId,
      roomId,
      status.name(),
      note,
      reviewerId,
      timestamp(updatedAt),
      version
    );
  }

  private static RoomJudgementRecord map(ResultSet rs, int row) throws SQLException {
    return new RoomJudgementRecord(
      rs.getLong("team_id"),
      rs.getLong("room_id"),
      JudgeStatus.valueOf(rs.getString("status")),
      rs.getString("note"),
      rs.getLong("version"),
      nullableLong(rs, "reviewer_id"),
      rs.getString("reviewer_name"),
      instant(rs, "updated_at")
    );
  }

  private static JudgementHistoryRecord mapHistory(ResultSet rs, int row) throws SQLException {
    String oldStatus = rs.getString("old_status");
    return new JudgementHistoryRecord(
      rs.getLong("id"),
      rs.getLong("team_id"),
      rs.getLong("room_id"),
      oldStatus == null ? null : JudgeStatus.valueOf(oldStatus),
      JudgeStatus.valueOf(rs.getString("new_status")),
      rs.getString("note"),
      rs.getLong("reviewer_id"),
      rs.getString("reviewer_name"),
      instant(rs, "changed_at"),
      rs.getLong("version")
    );
  }
}
