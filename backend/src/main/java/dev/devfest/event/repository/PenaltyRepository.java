package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.nullableLong;
import static dev.devfest.event.repository.EventJdbc.timestamp;

import dev.devfest.event.domain.PenaltyKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Penalties are never deleted; the database allows exactly one change per row: the void. */
@Repository
public class PenaltyRepository {

  private static final String SELECT = """
    SELECT p.id, p.team_id, p.room_id, p.kind, p.minutes, p.note, p.created_by, c.name AS created_by_name,
           p.created_at, p.voided_by, v.name AS voided_by_name, p.voided_at, p.void_reason
    FROM penalty p
    JOIN app_user c ON c.id = p.created_by
    LEFT JOIN app_user v ON v.id = p.voided_by
    """;

  private static final String NEWEST_FIRST = " ORDER BY p.created_at DESC, p.id DESC";

  private final JdbcTemplate db;

  public PenaltyRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Optional<PenaltyRecord> find(long penaltyId) {
    return db.query(SELECT + "WHERE p.id = ?", PenaltyRepository::map, penaltyId).stream().findFirst();
  }

  /** Newest first, active and voided. */
  public List<PenaltyRecord> findForTeam(long teamId) {
    return db.query(SELECT + "WHERE p.team_id = ?" + NEWEST_FIRST, PenaltyRepository::map, teamId);
  }

  /** Every team's penalties in the room, newest first (one statement for a whole room board). */
  public List<PenaltyRecord> findForRoom(long roomId) {
    return db.query(SELECT + "WHERE p.room_id = ?" + NEWEST_FIRST, PenaltyRepository::map, roomId);
  }

  /** Minutes come from the kind; {@code roomId} is null only for WRONG_ACCUSATION. */
  public long insert(long teamId, Long roomId, PenaltyKind kind, String note, long createdBy, Instant createdAt) {
    return db.queryForObject(
      """
      INSERT INTO penalty(team_id, room_id, kind, minutes, note, created_by, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""",
      Long.class,
      teamId,
      roomId,
      kind.name(),
      kind.minutes(),
      note,
      createdBy,
      timestamp(createdAt)
    );
  }

  /** The admin's signed time adjustment (−60..60, never 0); no room, the reason as note. */
  public long insertTimeAdjustment(long teamId, int minutes, String note, long createdBy, Instant createdAt) {
    return db.queryForObject(
      """
      INSERT INTO penalty(team_id, room_id, kind, minutes, note, created_by, created_at)
      VALUES (?, NULL, 'TIME_ADJUSTMENT', ?, ?, ?, ?) RETURNING id""",
      Long.class,
      teamId,
      minutes,
      note,
      createdBy,
      timestamp(createdAt)
    );
  }

  /** The team's single RECOVERY penalty; empty when the team already has one. */
  public Optional<Long> insertRecoveryOnce(long teamId, long roomId, long createdBy, Instant createdAt) {
    return db.queryForList(
      """
      INSERT INTO penalty(team_id, room_id, kind, minutes, note, created_by, created_at)
      VALUES (?, ?, 'RECOVERY', ?, '', ?, ?)
      ON CONFLICT (team_id) WHERE kind = 'RECOVERY' DO NOTHING
      RETURNING id""",
      Long.class,
      teamId,
      roomId,
      PenaltyKind.RECOVERY.minutes(),
      createdBy,
      timestamp(createdAt)
    ).stream().findFirst();
  }

  /** Returns false when the penalty does not exist or is already voided. */
  public boolean voidPenalty(long penaltyId, long voidedBy, Instant voidedAt, String reason) {
    return db.update(
      "UPDATE penalty SET voided_by = ?, voided_at = ?, void_reason = ? WHERE id = ? AND voided_at IS NULL",
      voidedBy,
      timestamp(voidedAt),
      reason,
      penaltyId
    ) == 1;
  }

  private static PenaltyRecord map(ResultSet rs, int row) throws SQLException {
    return new PenaltyRecord(
      rs.getLong("id"),
      rs.getLong("team_id"),
      nullableLong(rs, "room_id"),
      PenaltyKind.valueOf(rs.getString("kind")),
      rs.getInt("minutes"),
      rs.getString("note"),
      rs.getLong("created_by"),
      rs.getString("created_by_name"),
      instant(rs, "created_at"),
      nullableLong(rs, "voided_by"),
      rs.getString("voided_by_name"),
      instant(rs, "voided_at"),
      rs.getString("void_reason")
    );
  }
}
