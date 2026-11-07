package dev.devfest.event.dto;

import dev.devfest.event.domain.ContestProblem;
import dev.devfest.event.domain.GroupCode;
import java.time.Instant;
import java.util.List;

/**
 * The organizer page of the Problem Solving room (rules §20.5), for the room's manager and the ADMIN only. Teams are
 * grouped by the round in which their group visits the room. Every number is computed on this read from the typed
 * results; teams without a saved result have {@code saved} false, no place and zero scores.
 */
public record ContestResultsView(
  long roomId,
  String roomName,
  String contestUrl,
  boolean linkEditable,
  boolean clueKeySet,
  int maxProblemPoints,
  int maxTotal,
  int eligibleSolved,
  List<ProblemPoints> problems,
  List<RoundView> rounds,
  Instant generatedAt
) {
  public record ProblemPoints(ContestProblem problem, int points) {}

  public record RoundView(
    int round,
    GroupCode group,
    int teamCount,
    int savedCount,
    int eligibleCount,
    int releasedCount,
    int releasableCount,
    List<TeamRow> teams
  ) {}

  /**
   * {@code releasedNotEligible}: the clue was released and the results were edited below the eligibility line since;
   * the clue stays until the admin changes it by hand.
   */
  public record TeamRow(
    long teamId,
    String teamName,
    GroupCode group,
    int round,
    boolean saved,
    List<ContestProblem> solved,
    int penaltyMinutes,
    int solvedCount,
    int problemPoints,
    int bonus,
    int total,
    Integer place,
    boolean eligible,
    boolean released,
    Instant releasedAt,
    boolean releasedNotEligible,
    Instant savedAt
  ) {}
}
