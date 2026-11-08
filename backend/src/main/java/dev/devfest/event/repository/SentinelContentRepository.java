package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.timestamp;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The AI room's authored mission: one JSON document per room, replaced as a whole. */
@Repository
public class SentinelContentRepository {

  /** Per room with the activity: whether it can run a round (admin alerts). */
  public record RoomStats(long roomId, String roomName, boolean hasClueKey, boolean hasContent) {}

  // "~ '\\S'" = the key contains a non-whitespace character, as on the admin board.
  private static final String STATS_SQL = """
    SELECT r.id, r.name, r.clue_key ~ '\\S' AS has_clue_key,
           EXISTS(SELECT 1 FROM sentinel_content c WHERE c.room_id = r.id) AS has_content
    FROM event_room r
    WHERE r.activity = 'AI_QUIZ'
    ORDER BY r.position
    """;

  private final JdbcTemplate db;

  public SentinelContentRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** The stored document as JSON text, or empty when the room has none. */
  public Optional<String> document(long roomId) {
    return db.queryForList("SELECT document::text FROM sentinel_content WHERE room_id = ?", String.class, roomId).stream().findFirst();
  }

  public void replace(long roomId, String documentJson, long userId, Instant at) {
    db.update(
      """
      INSERT INTO sentinel_content(room_id, document, updated_by, updated_at) VALUES (?, ?::jsonb, ?, ?)
      ON CONFLICT (room_id) DO UPDATE SET document = EXCLUDED.document, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at""",
      roomId,
      documentJson,
      userId,
      timestamp(at)
    );
  }

  public List<RoomStats> stats() {
    return db.query(
      STATS_SQL,
      (rs, row) -> new RoomStats(rs.getLong("id"), rs.getString("name"), rs.getBoolean("has_clue_key"), rs.getBoolean("has_content"))
    );
  }
}
