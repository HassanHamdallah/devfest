package dev.devfest.event.dto;

import java.time.Instant;

public record SnapshotView(
  long id,
  Instant createdAt,
  String createdBy,
  String kind,
  String reason,
  Long teamId,
  String teamName
) {}
