package dev.devfest.event.repository;

import java.time.Instant;

/**
 * One room_terminal_file row. {@code content} is null in listings ({@link RoomTerminalFileRepository#summaries}),
 * which never read the file text.
 */
public record TerminalFileRecord(
  long id,
  long roomId,
  String path,
  int sizeBytes,
  String updatedByName,
  Instant updatedAt,
  String content
) {}
