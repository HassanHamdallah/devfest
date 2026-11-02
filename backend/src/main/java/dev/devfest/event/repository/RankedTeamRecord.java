package dev.devfest.event.repository;

import dev.devfest.event.domain.GroupCode;
import java.time.Instant;

/**
 * One active team in ranking order. {@code rank}, {@code finishedAt} and {@code adjustedSeconds}
 * are null for teams that have not finished. Penalty figures count active (non-voided) penalties only.
 */
public record RankedTeamRecord(
  Integer rank,
  long teamId,
  String teamName,
  GroupCode groupCode,
  int clues,
  int penaltyMinutes,
  int penaltyEntries,
  boolean finished,
  Instant finishedAt,
  Long adjustedSeconds,
  boolean recoveryUsed,
  int accusationAttempts
) {}
