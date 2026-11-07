package dev.devfest.event.repository;

import dev.devfest.event.domain.ContestProblem;
import dev.devfest.event.domain.GroupCode;
import java.time.Instant;
import java.util.Set;

/**
 * One active, not disqualified team with what the contest room holds for it. {@code solved} and {@code penaltyMinutes}
 * are meaningful only when {@code saved}; {@code releasedAt} is null until the clue was released.
 */
public record ContestTeamRecord(
  long teamId,
  String teamName,
  GroupCode groupCode,
  boolean saved,
  Set<ContestProblem> solved,
  int penaltyMinutes,
  Instant savedAt,
  Instant releasedAt
) {
  public boolean released() {
    return releasedAt != null;
  }
}
