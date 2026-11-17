package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;
import static dev.devfest.event.repository.EventJdbc.timestamp;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The final's one terminal package (table final_terminal_bundle), kept apart from every room's package: no statement
 * here reads or writes room_terminal_bundle. Every read except {@link #archive} leaves the BYTEA column out.
 */
@Repository
public class FinalTerminalBundleRepository {

  /** The final's package without its archive. */
  public record Stored(
    String sha256,
    int archiveBytes,
    int fileCount,
    long unpackedBytes,
    String owners,
    String etcPasswd,
    String etcGroup,
    String uploadedByName,
    Instant uploadedAt
  ) {}

  private final JdbcTemplate db;

  public FinalTerminalBundleRepository(JdbcTemplate db) {
    this.db = db;
  }

  public Optional<Stored> find() {
    return db
      .query(
        """
        SELECT b.sha256, b.archive_bytes, b.file_count, b.unpacked_bytes, b.owners, b.etc_passwd, b.etc_group,
               u.name AS uploaded_by_name, b.uploaded_at
        FROM final_terminal_bundle b JOIN app_user u ON u.id = b.uploaded_by
        WHERE b.id = 1""",
        (rs, row) ->
          new Stored(
            rs.getString("sha256"),
            rs.getInt("archive_bytes"),
            rs.getInt("file_count"),
            rs.getLong("unpacked_bytes"),
            rs.getString("owners"),
            rs.getString("etc_passwd"),
            rs.getString("etc_group"),
            rs.getString("uploaded_by_name"),
            instant(rs, "uploaded_at")
          )
      )
      .stream()
      .findFirst();
  }

  /** The archive with its digest, read by one statement. */
  public Optional<TerminalBundleBytes> archive() {
    return db
      .query(
        "SELECT sha256, archive FROM final_terminal_bundle WHERE id = 1",
        (rs, row) -> new TerminalBundleBytes(rs.getString("sha256"), rs.getBytes("archive"))
      )
      .stream()
      .findFirst();
  }

  /** Inserts or replaces the final's package. */
  public void save(
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
      INSERT INTO final_terminal_bundle(id, archive, archive_bytes, sha256, file_count, unpacked_bytes, owners, etc_passwd,
                                        etc_group, uploaded_by, uploaded_at)
      VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (id) DO UPDATE SET archive = EXCLUDED.archive, archive_bytes = EXCLUDED.archive_bytes,
        sha256 = EXCLUDED.sha256, file_count = EXCLUDED.file_count, unpacked_bytes = EXCLUDED.unpacked_bytes,
        owners = EXCLUDED.owners, etc_passwd = EXCLUDED.etc_passwd, etc_group = EXCLUDED.etc_group,
        uploaded_by = EXCLUDED.uploaded_by, uploaded_at = EXCLUDED.uploaded_at""",
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

  public boolean delete() {
    return db.update("DELETE FROM final_terminal_bundle WHERE id = 1") == 1;
  }
}
