package dev.devfest.event.domain;

import java.util.Set;

/**
 * The score of the Problem Solving room (rules §20.4), a pure function of the typed results. Nothing here is stored:
 * every read computes it again.
 *
 * <ul>
 *   <li>Problem points: each solved problem of A to F is worth {@value ContestProblem#POINTS} (at most
 *       {@value #MAX_PROBLEM_POINTS}).
 *   <li>Bonus: from the standing the organizer picked (1st = 3, 2nd = 2, 3rd = 1, Other = 0). Ties are the
 *       organizer's choice, two teams may both be 1st. A team that solved nothing gets no bonus. No standing yet
 *       (a result saved before standings existed) counts as Other.
 *   <li>Total out of {@value #MAX_TOTAL}. A team is eligible for the room's clue with at least
 *       {@value #ELIGIBLE_SOLVED} problems solved.
 * </ul>
 */
public final class ContestScoring {

  public static final int PROBLEM_COUNT = ContestProblem.values().length;
  public static final int MAX_PROBLEM_POINTS = PROBLEM_COUNT * ContestProblem.POINTS;
  public static final int MAX_BONUS = ContestStanding.FIRST.bonus();
  public static final int MAX_TOTAL = MAX_PROBLEM_POINTS + MAX_BONUS;
  public static final int ELIGIBLE_SOLVED = 3;

  /** The computed score of one team with a saved result; {@code standing} is never null (Other when none was picked). */
  public record Score(int solvedCount, int problemPoints, ContestStanding standing, int bonus, int total, boolean eligible) {}

  private ContestScoring() {}

  public static int problemPoints(Set<ContestProblem> solved) {
    return solved.size() * ContestProblem.POINTS;
  }

  public static boolean eligible(int solvedCount) {
    return solvedCount >= ELIGIBLE_SOLVED;
  }

  /** The standing's bonus, and 0 whatever the standing when nothing was solved. */
  public static int bonus(ContestStanding standing, int solvedCount) {
    return standing == null || solvedCount == 0 ? 0 : standing.bonus();
  }

  public static Score score(Set<ContestProblem> solved, ContestStanding standing) {
    ContestStanding effective = standing == null ? ContestStanding.OTHER : standing;
    int solvedCount = solved.size();
    int points = problemPoints(solved);
    int bonus = bonus(effective, solvedCount);
    return new Score(solvedCount, points, effective, bonus, points + bonus, eligible(solvedCount));
  }
}
