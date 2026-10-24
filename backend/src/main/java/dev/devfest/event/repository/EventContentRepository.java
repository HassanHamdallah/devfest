package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.nullableLong;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The final's shared content: encrypted message and correct suspect (versioned). */
@Repository
public class EventContentRepository {

  private final JdbcTemplate db;

  public EventContentRepository(JdbcTemplate db) {
    this.db = db;
  }

  public EventContentRecord get() {
    return db.queryForObject(
      "SELECT encrypted_message, correct_suspect_id, version, updated_at FROM event_content WHERE id = 1",
      (rs, row) ->
        new EventContentRecord(
          rs.getString("encrypted_message"),
          nullableLong(rs, "correct_suspect_id"),
          rs.getLong("version"),
          instant(rs, "updated_at")
        )
    );
  }

  /** Optimistic write: returns false (nothing written) when {@code expectedVersion} is stale. */
  public boolean update(long expectedVersion, String encryptedMessage, Long correctSuspectId) {
    return db.update(
      """
      UPDATE event_content
      SET encrypted_message = ?, correct_suspect_id = ?, version = version + 1, updated_at = now()
      WHERE id = 1 AND version = ?""",
      encryptedMessage,
      correctSuspectId,
      expectedVersion
    ) == 1;
  }
}
