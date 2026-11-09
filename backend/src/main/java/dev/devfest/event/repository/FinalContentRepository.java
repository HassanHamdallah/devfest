package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The final's one validated content file (absent until the admin imports it). */
@Repository
public class FinalContentRepository {

  public record Stored(String json, int fullUntilMinutes, Instant importedAt) {}

  private final JdbcTemplate db;

  public FinalContentRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Optional<Stored> find() {
    return db.query(
      "SELECT content::text AS json, full_until_minutes, imported_at FROM final_content WHERE id = 1",
      (rs, row) -> new Stored(rs.getString("json"), rs.getInt("full_until_minutes"), instant(rs, "imported_at"))
    ).stream().findFirst();
  }

  public void save(String json, int fullUntilMinutes, long importedBy, Instant at) {
    db.update(
      """
      INSERT INTO final_content(id, content, full_until_minutes, imported_by, imported_at) VALUES (1, ?::jsonb, ?, ?, ?)
      ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, full_until_minutes = EXCLUDED.full_until_minutes,
        imported_by = EXCLUDED.imported_by, imported_at = EXCLUDED.imported_at""",
      json,
      fullUntilMinutes,
      importedBy,
      timestamp(at)
    );
  }
}
