package dev.devfest.event.dto;

import java.time.Instant;

/** The admin's view of the final content: whether a file is imported, its summary and whether it is locked. */
public record FinalContentView(
  boolean imported,
  boolean readOnly,
  Instant importedAt,
  Integer fullUntilMinutes,
  String content
) {}
