package dev.devfest.event.repository;

/**
 * A team's final-relevant counters in one row. {@code missingRoomId} is the first room (by position) without a
 * collected clue, null when all four are collected; {@code finish} is null until the team solves the case.
 * Penalty minutes count active penalties only.
 */
public record TeamFinalSummaryRecord(
  int clues,
  Long missingRoomId,
  int recoveryAttempts,
  int accusationAttempts,
  int wrongAccusations,
  int penaltyMinutes,
  TeamFinishRecord finish
) {
  public boolean finished() {
    return finish != null;
  }
}
