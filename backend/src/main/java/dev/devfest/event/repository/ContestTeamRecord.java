package dev.devfest.event.repository;

import dev.devfest.event.domain.ContestProblem;
import dev.devfest.event.domain.ContestStanding;
import dev.devfest.event.domain.GroupCode;
import java.time.Instant;
import java.util.Set;

/**
 * One active, not disqualified team with what the contest room holds for it. {@code solved} and {@code standing}
 * are meaningful only when {@code saved} ({@code standing} is null for a result saved before standings existed, which
 * scores as Other); {@code releasedAt} is null until the clue was released.
 */
public record ContestTeamRecord(
  long teamId,
  String teamName,
  GroupCode groupCode,
  boolean saved,
  Set<ContestProblem> solved,
  ContestStanding standing,
  Instant savedAt,
  Instant releasedAt
) {
  public boolean released() {
    return releasedAt != null;
  }
}
