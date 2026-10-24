package dev.devfest.event.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The (at most six) suspects of the final, each in a display position 0..5. */
@Repository
public class SuspectRepository {

  public static final int MAX_SUSPECTS = 6;

  private static final String COLUMNS = "id, name, description, position";

  private final JdbcTemplate db;

  public SuspectRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<SuspectRecord> findAll() {
    return db.query("SELECT " + COLUMNS + " FROM suspect ORDER BY position, id", SuspectRepository::map);
  }

  public Optional<SuspectRecord> find(long suspectId) {
    return db.query("SELECT " + COLUMNS + " FROM suspect WHERE id = ?", SuspectRepository::map, suspectId)
      .stream()
      .findFirst();
  }

  public int count() {
    return db.queryForObject("SELECT count(*) FROM suspect", Integer.class);
  }

  /** The lowest unused position, empty when all six are taken. */
  public OptionalInt firstFreePosition() {
    List<Integer> free = db.queryForList(
      """
      SELECT p FROM generate_series(0, ?) AS p
      WHERE NOT EXISTS (SELECT 1 FROM suspect s WHERE s.position = p)
      ORDER BY p LIMIT 1""",
      Integer.class,
      MAX_SUSPECTS - 1
    );
    return free.isEmpty() ? OptionalInt.empty() : OptionalInt.of(free.getFirst());
  }

  public long insert(String name, String description, int position) {
    return db.queryForObject(
      "INSERT INTO suspect(name, description, position) VALUES (?, ?, ?) RETURNING id",
      Long.class,
      name,
      description,
      position
    );
  }

  public void update(long suspectId, String name, String description) {
    db.update("UPDATE suspect SET name = ?, description = ? WHERE id = ?", name, description, suspectId);
  }

  /** Callers first rule out the correct suspect and accused suspects (both are foreign keys). */
  public void delete(long suspectId) {
    db.update("DELETE FROM suspect WHERE id = ?", suspectId);
  }

  private static SuspectRecord map(ResultSet rs, int row) throws SQLException {
    return new SuspectRecord(rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getInt("position"));
  }
}
