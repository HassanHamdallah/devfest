package dev.devfest.event.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The score of the Problem Solving room (rules §20.4), a pure function of the typed results. Nothing here is stored:
 * every read computes it again.
 *
 * <ul>
 *   <li>Problem points: A=2, B=3, C=3, D=2, E=2 (at most {@value #MAX_PROBLEM_POINTS}).
 *   <li>Placement inside one round, among the teams that have a saved result: more problems solved first, then the
 *       lower penalty. Teams with the same solved count and penalty share a place, and the next place skips the
 *       tied ones (1, 1, 3, 4).
 *   <li>Bonus by place: 1st = 3, 2nd = 2, 3rd = 1, anything lower = 0. Two teams tied first therefore get 3 and 3,
 *       the next team (third) gets 1 and the one after it 0. A team that solved nothing gets no bonus.
 *   <li>Total out of {@value #MAX_TOTAL}. A team is eligible for the room's clue with at least
 *       {@value #ELIGIBLE_SOLVED} problems solved.
 * </ul>
 */
public final class ContestScoring {

  public static final int MAX_PROBLEM_POINTS = 12;
  public static final int MAX_BONUS = 3;
  public static final int MAX_TOTAL = MAX_PROBLEM_POINTS + MAX_BONUS;
  public static final int ELIGIBLE_SOLVED = 3;
  private static final int[] BONUS_BY_PLACE = { 0, 3, 2, 1 };

  /** One team's saved result; {@code penaltyMinutes} are whole minutes, never negative. */
  public record Result(long teamId, Set<ContestProblem> solved, int penaltyMinutes) {
    public Result {
      if (penaltyMinutes < 0) throw new IllegalArgumentException("The penalty cannot be negative.");
      solved = solved.isEmpty() ? EnumSet.noneOf(ContestProblem.class) : EnumSet.copyOf(solved);
    }
  }

  /** The computed score of one team with a saved result. */
  public record Score(int solvedCount, int problemPoints, int place, int bonus, int total, boolean eligible) {}

  private ContestScoring() {}

  public static int problemPoints(Set<ContestProblem> solved) {
    return solved.stream().mapToInt(ContestProblem::points).sum();
  }

  public static boolean eligible(int solvedCount) {
    return solvedCount >= ELIGIBLE_SOLVED;
  }

  /** 3, 2 or 1 for the first three places, otherwise 0, and 0 whatever the place when nothing was solved. */
  public static int bonus(int place, int solvedCount) {
    if (solvedCount == 0 || place < 1 || place >= BONUS_BY_PLACE.length) return 0;
    return BONUS_BY_PLACE[place];
  }

  /**
   * Scores the teams of ONE round, keyed by team id. Teams without a saved result are not passed in and take no
   * place.
   */
  public static Map<Long, Score> scoreRound(List<Result> round) {
    List<Result> ordered = new ArrayList<>(round);
    ordered.sort(BEST_FIRST);
    Map<Long, Score> scores = new HashMap<>();
    int place = 0;
    Result previous = null;
    for (int index = 0; index < ordered.size(); index++) {
      Result result = ordered.get(index);
      if (previous == null || BEST_FIRST.compare(previous, result) != 0) place = index + 1;
      int solvedCount = result.solved().size();
      int points = problemPoints(result.solved());
      int bonus = bonus(place, solvedCount);
      scores.put(result.teamId(), new Score(solvedCount, points, place, bonus, points + bonus, eligible(solvedCount)));
      previous = result;
    }
    return scores;
  }

  /** More solved first, then the lower penalty; equal on both compares as 0 (a shared place). */
  private static final Comparator<Result> BEST_FIRST = Comparator
    .<Result>comparingInt(result -> result.solved().size())
    .reversed()
    .thenComparingInt(Result::penaltyMinutes);
}
