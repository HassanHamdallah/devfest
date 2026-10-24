package dev.devfest.event.repository;

import java.time.Instant;

/** One event_snapshot row without its data; {@code teamId}/{@code teamName} only for RESET_TEAM. */
public record SnapshotRecord(
  long id,
  Instant createdAt,
  String createdByName,
  String kind,
  String reason,
  Long teamId,
  String teamName
) {}
