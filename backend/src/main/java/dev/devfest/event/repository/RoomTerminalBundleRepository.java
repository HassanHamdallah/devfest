package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * A room's terminal server package. Every read except {@link #archive} leaves the BYTEA column out, so boards,
 * lists and the ETag check never load the blob.
 */
@Repository
public class RoomTerminalBundleRepository {

  private final JdbcTemplate db;

  public RoomTerminalBundleRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** The package's digest, counts and accounts; the archive is not read. */
  public Optional<TerminalBundleRecord> find(long roomId) {
    return db
      .query(
        """
        SELECT b.room_id, b.sha256, b.archive_bytes, b.file_count, b.unpacked_bytes, b.owners, b.etc_passwd,
               b.etc_group, u.name AS uploaded_by_name, b.uploaded_at, b.terminal_clock
        FROM room_terminal_bundle b JOIN app_user u ON u.id = b.uploaded_by
        WHERE b.room_id = ?""",
        RoomTerminalBundleRepository::map,
        roomId
      )
      .stream()
      .findFirst();
  }

  /** The archive with its digest; the whole value is read and the connection is released before this returns. */
  public Optional<TerminalBundleBytes> archive(long roomId) {
    return db
      .query(
        "SELECT sha256, archive FROM room_terminal_bundle WHERE room_id = ?",
        (rs, row) -> new TerminalBundleBytes(rs.getString("sha256"), rs.getBytes("archive")),
        roomId
      )
      .stream()
      .findFirst();
  }

  /** Inserts or replaces the room's package. */
  public void save(
    long roomId,
    byte[] archive,
    String sha256,
    int fileCount,
    long unpackedBytes,
    String owners,
    String etcPasswd,
    String etcGroup,
    long uploadedBy,
    Instant at
  ) {
    db.update(
      """
      INSERT INTO room_terminal_bundle(room_id, archive, archive_bytes, sha256, file_count, unpacked_bytes, owners,
                                       etc_passwd, etc_group, uploaded_by, uploaded_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (room_id) DO UPDATE SET archive = EXCLUDED.archive, archive_bytes = EXCLUDED.archive_bytes,
        sha256 = EXCLUDED.sha256, file_count = EXCLUDED.file_count, unpacked_bytes = EXCLUDED.unpacked_bytes,
        owners = EXCLUDED.owners, etc_passwd = EXCLUDED.etc_passwd, etc_group = EXCLUDED.etc_group,
        uploaded_by = EXCLUDED.uploaded_by, uploaded_at = EXCLUDED.uploaded_at""",
      roomId,
      archive,
      archive.length,
      sha256,
      fileCount,
      unpackedBytes,
      owners,
      etcPasswd,
      etcGroup,
      uploadedBy,
      timestamp(at)
    );
  }

  /** Sets or (null) clears the terminal clock of the room's package; false when the room has no package. */
  public boolean setClock(long roomId, Instant clock) {
    return db.update("UPDATE room_terminal_bundle SET terminal_clock = ? WHERE room_id = ?", timestamp(clock), roomId) == 1;
  }

  public boolean delete(long roomId) {
    return db.update("DELETE FROM room_terminal_bundle WHERE room_id = ?", roomId) == 1;
  }

  private static TerminalBundleRecord map(ResultSet rs, int row) throws SQLException {
    return new TerminalBundleRecord(
      rs.getLong("room_id"),
      rs.getString("sha256"),
      rs.getInt("archive_bytes"),
      rs.getInt("file_count"),
      rs.getLong("unpacked_bytes"),
      rs.getString("owners"),
      rs.getString("etc_passwd"),
      rs.getString("etc_group"),
      rs.getString("uploaded_by_name"),
      instant(rs, "uploaded_at"),
      instant(rs, "terminal_clock")
    );
  }
}
