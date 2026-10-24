package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import java.time.Instant;

/** The admin's ranking row; {@code rank}, {@code finishedAt} and {@code adjustedSeconds} stay null until a team finishes. */
public record RankingRow(
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
  boolean disqualified
) {}
