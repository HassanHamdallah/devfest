package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;

/** One published ranking line; {@code rank} and {@code adjustedSeconds} are null until the team finishes. */
public record PublicRankingRow(
  Integer rank,
  String teamName,
  GroupCode groupCode,
  int clues,
  int penaltyMinutes,
  boolean finished,
  Long adjustedSeconds,
  boolean disqualified
) {}
