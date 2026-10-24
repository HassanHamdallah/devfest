package dev.devfest.event.repository;

import dev.devfest.event.domain.GroupCode;
import java.time.Instant;

/** One event_team row. */
public record EventTeamRecord(
  long id,
  String name,
  GroupCode groupCode,
  long accountId,
  boolean active,
  Instant createdAt
) {}
