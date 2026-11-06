package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;

import dev.devfest.event.domain.TerminalAnswerKeys;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The organizer's answer keys of the Linux terminal rooms (never sent to teams). */
@Repository
public class TerminalAnswerKeyRepository {

  /** A room's keys; the form rooms without a row have {@link TerminalAnswerKeys#empty()}. */
  public record StoredKeys(TerminalAnswerKeys keys, Instant updatedAt) {}

  private final JdbcTemplate db;

  public TerminalAnswerKeyRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Optional<StoredKeys> find(long roomId) {
    return db
      .query(
        """
        SELECT path_keys, username_keys, file_size_keys, pattern_keys, updated_at
        FROM terminal_answer_key WHERE room_id = ?""",
        (rs, row) -> new StoredKeys(map(rs), instant(rs, "updated_at")),
        roomId
      )
      .stream()
      .findFirst();
  }

  /** The keys of every room with the Linux terminal activity (empty keys when never saved), one statement. */
  public Map<Long, TerminalAnswerKeys> forFormRooms() {
    Map<Long, TerminalAnswerKeys> keys = new LinkedHashMap<>();
    db.query(
      """
      SELECT r.id, COALESCE(k.path_keys, '') AS path_keys, COALESCE(k.username_keys, '') AS username_keys,
             COALESCE(k.file_size_keys, '') AS file_size_keys, COALESCE(k.pattern_keys, '') AS pattern_keys
      FROM event_room r LEFT JOIN terminal_answer_key k ON k.room_id = r.id
      WHERE r.activity = 'LINUX_TERMINAL' ORDER BY r.position""",
      rs -> {
        keys.put(rs.getLong("id"), map(rs));
      }
    );
    return keys;
  }

  public void save(long roomId, TerminalAnswerKeys keys, long updatedBy, Instant at) {
    db.update(
      """
      INSERT INTO terminal_answer_key(room_id, path_keys, username_keys, file_size_keys, pattern_keys,
                                      updated_by, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (room_id) DO UPDATE SET path_keys = EXCLUDED.path_keys, username_keys = EXCLUDED.username_keys,
        file_size_keys = EXCLUDED.file_size_keys, pattern_keys = EXCLUDED.pattern_keys,
        updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at""",
      roomId,
      keys.path(),
      keys.username(),
      keys.fileSize(),
      keys.pattern(),
      updatedBy,
      timestamp(at)
    );
  }

  private static TerminalAnswerKeys map(ResultSet rs) throws SQLException {
    return new TerminalAnswerKeys(
      rs.getString("path_keys"),
      rs.getString("username_keys"),
      rs.getString("file_size_keys"),
      rs.getString("pattern_keys")
    );
  }
}
