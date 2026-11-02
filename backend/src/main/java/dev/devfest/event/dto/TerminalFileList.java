package dev.devfest.event.dto;

import java.time.Instant;
import java.util.List;

/**
 * The manager's view of a room's terminal files (no content). {@code editable} is false once the final has
 * opened; the limits are sent so the screen can show the usage against them.
 */
public record TerminalFileList(
  long roomId,
  boolean terminalEnabled,
  boolean editable,
  List<Entry> files,
  long totalBytes,
  int maxFiles,
  int maxFileBytes,
  int maxTotalBytes
) {
  public record Entry(long id, String path, int sizeBytes, String updatedBy, Instant updatedAt) {}
}
