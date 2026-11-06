package dev.devfest.event.dto;

import java.time.Instant;
import java.util.List;

/**
 * The manager's view of a room's terminal files (no content). {@code editable} is false once the final has
 * opened; the limits are sent so the screen can show the usage against them. {@code bundle} describes the room's
 * server package (null when it has none) and {@code maxBundleUploadBytes} is the upload limit.
 */
public record TerminalFileList(
  long roomId,
  boolean terminalEnabled,
  boolean editable,
  List<Entry> files,
  long totalBytes,
  int maxFiles,
  int maxFileBytes,
  int maxTotalBytes,
  TerminalBundleView bundle,
  int maxBundleUploadBytes
) {
  public record Entry(long id, String path, int sizeBytes, String updatedBy, Instant updatedAt) {}
}
