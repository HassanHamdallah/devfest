package dev.devfest.event.dto;

import java.time.Instant;
import java.util.List;

/**
 * The admin's summary of the final's terminal package (never the archive itself). {@code uploaded} is false (and the
 * other fields null) while there is none; {@code readOnly} once the final has opened.
 */
public record FinalTerminalBundleView(
  boolean uploaded,
  boolean readOnly,
  String sha256,
  Integer fileCount,
  Long unpackedBytes,
  Integer archiveBytes,
  List<String> owners,
  String uploadedBy,
  Instant uploadedAt
) {}
