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

/** The text files of a room's in-browser terminal (paths relative to the team's home directory). */
@Repository
public class RoomTerminalFileRepository {

  /** Room usage: number of files and their total size in bytes. */
  public record Usage(int files, long totalBytes) {}

  private static final String SUMMARY_COLUMNS = """
    SELECT f.id, f.room_id, f.path, f.size_bytes, u.name AS updated_by_name, f.updated_at, NULL AS content
    FROM room_terminal_file f
    JOIN app_user u ON u.id = f.updated_by
    """;

  private final JdbcTemplate db;

  public RoomTerminalFileRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** Path, size and last editor of every file of the room, by path; the content is not read. */
  public List<TerminalFileRecord> summaries(long roomId) {
    return db.query(SUMMARY_COLUMNS + "WHERE f.room_id = ? ORDER BY f.path", RoomTerminalFileRepository::map, roomId);
  }

  /** Every file of the room with its content, by path (what a terminal starts with). */
  public List<TerminalFileRecord> withContent(long roomId) {
    return db.query(
      """
      SELECT f.id, f.room_id, f.path, f.size_bytes, NULL AS updated_by_name, f.updated_at, f.content
      FROM room_terminal_file f WHERE f.room_id = ? ORDER BY f.path""",
      RoomTerminalFileRepository::map,
      roomId
    );
  }

  public Optional<TerminalFileRecord> find(long roomId, long fileId) {
    return db
      .query(
        """
        SELECT f.id, f.room_id, f.path, f.size_bytes, u.name AS updated_by_name, f.updated_at, f.content
        FROM room_terminal_file f JOIN app_user u ON u.id = f.updated_by
        WHERE f.room_id = ? AND f.id = ?""",
        RoomTerminalFileRepository::map,
        roomId,
        fileId
      )
      .stream()
      .findFirst();
  }

  public Usage usage(long roomId) {
    return db.queryForObject(
      "SELECT count(*) AS files, COALESCE(sum(size_bytes), 0) AS total FROM room_terminal_file WHERE room_id = ?",
      (rs, row) -> new Usage(rs.getInt("files"), rs.getLong("total")),
      roomId
    );
  }

  /** True when another file of the room ({@code exceptId} excluded, null = none) already has {@code path}. */
  public boolean pathTaken(long roomId, String path, Long exceptId) {
    return Boolean.TRUE.equals(
      db.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM room_terminal_file WHERE room_id = ? AND path = ? AND id IS DISTINCT FROM CAST(? AS BIGINT))",
        Boolean.class,
        roomId,
        path,
        exceptId
      )
    );
  }

  public long insert(long roomId, String path, String content, int sizeBytes, long updatedBy, Instant at) {
    return db.queryForObject(
      """
      INSERT INTO room_terminal_file(room_id, path, content, size_bytes, updated_by, updated_at)
      VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
      Long.class,
      roomId,
      path,
      content,
      sizeBytes,
      updatedBy,
      timestamp(at)
    );
  }

  public boolean update(long roomId, long fileId, String path, String content, int sizeBytes, long updatedBy, Instant at) {
    return db.update(
      """
      UPDATE room_terminal_file SET path = ?, content = ?, size_bytes = ?, updated_by = ?, updated_at = ?
      WHERE room_id = ? AND id = ?""",
      path,
      content,
      sizeBytes,
      updatedBy,
      timestamp(at),
      roomId,
      fileId
    ) == 1;
  }

  public boolean delete(long roomId, long fileId) {
    return db.update("DELETE FROM room_terminal_file WHERE room_id = ? AND id = ?", roomId, fileId) == 1;
  }

  private static TerminalFileRecord map(ResultSet rs, int row) throws SQLException {
    return new TerminalFileRecord(
      rs.getLong("id"),
      rs.getLong("room_id"),
      rs.getString("path"),
      rs.getInt("size_bytes"),
      rs.getString("updated_by_name"),
      instant(rs, "updated_at"),
      rs.getString("content")
    );
  }
}
