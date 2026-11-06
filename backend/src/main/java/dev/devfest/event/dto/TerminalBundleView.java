package dev.devfest.event.dto;

import java.time.Instant;
import java.util.List;

/** The manager's summary of a room's server package (never the archive itself); {@code clock} is the terminal clock or null. */
public record TerminalBundleView(
  String sha256,
  int fileCount,
  long unpackedBytes,
  int archiveBytes,
  List<String> owners,
  String uploadedBy,
  Instant uploadedAt,
  Instant clock
) {}
