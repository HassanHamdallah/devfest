package dev.devfest.event.repository;

import dev.devfest.event.domain.GroupCode;
import java.time.Instant;

/** One event_team row; a disqualified team keeps its records but can no longer submit anything. */
public record EventTeamRecord(
  long id,
  String name,
  GroupCode groupCode,
  long accountId,
  boolean active,
  Instant createdAt,
  boolean disqualified
) {}
