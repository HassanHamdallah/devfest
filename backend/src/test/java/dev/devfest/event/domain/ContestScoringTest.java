package dev.devfest.event.domain;

import static dev.devfest.event.domain.ContestProblem.A;
import static dev.devfest.event.domain.ContestProblem.B;
import static dev.devfest.event.domain.ContestProblem.C;
import static dev.devfest.event.domain.ContestProblem.D;
import static dev.devfest.event.domain.ContestProblem.E;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.ContestScoring.Result;
import dev.devfest.event.domain.ContestScoring.Score;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContestScoringTest {

  private static Set<ContestProblem> solved(ContestProblem... problems) {
    return problems.length == 0 ? EnumSet.noneOf(ContestProblem.class) : EnumSet.of(problems[0], problems);
  }

  private static Result result(long team, int penalty, ContestProblem... problems) {
    return new Result(team, solved(problems), penalty);
  }

  // ---- problem points ----------------------------------------------------------------------------

  @Test
  void eachProblemIsWorthItsOwnPointsAndAllFiveMakeTwelve() {
    assertEquals(2, A.points());
    assertEquals(3, B.points());
    assertEquals(3, C.points());
    assertEquals(2, D.points());
    assertEquals(2, E.points());
    assertEquals(12, ContestScoring.problemPoints(solved(A, B, C, D, E)));
    assertEquals(ContestScoring.MAX_PROBLEM_POINTS, ContestScoring.problemPoints(solved(A, B, C, D, E)));
    assertEquals(0, ContestScoring.problemPoints(solved()));
    assertEquals(5, ContestScoring.problemPoints(solved(A, C)), "A (2) and C (3)");
    assertEquals(8, ContestScoring.problemPoints(solved(B, C, E)), "B (3), C (3) and E (2)");
    assertEquals(15, ContestScoring.MAX_TOTAL);
  }

  // ---- bonus by place ----------------------------------------------------------------------------

  @Test
  void theBonusIsThreeTwoOneThenZero() {
    assertEquals(3, ContestScoring.bonus(1, 5));
    assertEquals(2, ContestScoring.bonus(2, 5));
    assertEquals(1, ContestScoring.bonus(3, 5));
    assertEquals(0, ContestScoring.bonus(4, 5));
    assertEquals(0, ContestScoring.bonus(9, 5));
  }

  @Test
  void aTeamWithNothingSolvedGetsNoBonusWhateverItsPlace() {
    assertEquals(0, ContestScoring.bonus(1, 0));
    assertEquals(0, ContestScoring.bonus(2, 0));
    assertEquals(0, ContestScoring.bonus(3, 0));
  }

  // ---- placement ---------------------------------------------------------------------------------

  @Test
  void moreSolvedWinsThenTheLowerPenalty() {
    Map<Long, Score> scores = ContestScoring.scoreRound(
      List.of(result(1, 300, A, B, C, D), result(2, 10, A, B, C), result(3, 50, A, B, C), result(4, 500, A, B, C, D, E))
    );
    assertEquals(1, scores.get(4L).place(), "five solved beat four whatever the penalty");
    assertEquals(2, scores.get(1L).place());
    assertEquals(3, scores.get(2L).place(), "three solved: the lower penalty (10) first");
    assertEquals(4, scores.get(3L).place());
    assertEquals(List.of(3, 2, 1, 0), List.of(scores.get(4L).bonus(), scores.get(1L).bonus(), scores.get(2L).bonus(), scores.get(3L).bonus()));
  }

  @Test
  void fewerSolvedNeverBeatsMoreEvenWithAZeroPenalty() {
    Map<Long, Score> scores = ContestScoring.scoreRound(List.of(result(1, 0, A, B), result(2, 9999, A, B, C)));
    assertEquals(1, scores.get(2L).place());
    assertEquals(2, scores.get(1L).place());
  }

  @Test
  void twoTeamsTiedFirstGetThreeAndThreeThenOneThenZero() {
    Map<Long, Score> scores = ContestScoring.scoreRound(
      List.of(
        result(1, 40, A, B, C),
        result(2, 40, C, B, A),
        result(3, 41, A, B, C),
        result(4, 60, A, B, C)
      )
    );
    assertEquals(1, scores.get(1L).place());
    assertEquals(1, scores.get(2L).place(), "equal solved and penalty share a place");
    assertEquals(3, scores.get(3L).place(), "the next place skips the tied ones");
    assertEquals(4, scores.get(4L).place());
    assertEquals(3, scores.get(1L).bonus());
    assertEquals(3, scores.get(2L).bonus());
    assertEquals(1, scores.get(3L).bonus());
    assertEquals(0, scores.get(4L).bonus());
    assertEquals(8 + 3, scores.get(1L).total(), "A + B + C = 8 problem points plus 3");
    assertEquals(8 + 1, scores.get(3L).total());
    assertEquals(8, scores.get(4L).total());
  }

  @Test
  void aTieForSecondLeavesThirdEmpty() {
    Map<Long, Score> scores = ContestScoring.scoreRound(
      List.of(result(1, 5, A, B, C, D), result(2, 20, A, B, C), result(3, 20, A, B, C), result(4, 90, A, B, C))
    );
    assertEquals(List.of(1, 2, 2, 4), List.of(scores.get(1L).place(), scores.get(2L).place(), scores.get(3L).place(), scores.get(4L).place()));
    assertEquals(List.of(3, 2, 2, 0), List.of(scores.get(1L).bonus(), scores.get(2L).bonus(), scores.get(3L).bonus(), scores.get(4L).bonus()));
  }

  @Test
  void allTeamsTiedShareFirstPlaceAndTheBonusOfThree() {
    Map<Long, Score> scores = ContestScoring.scoreRound(List.of(result(1, 0, A, B, C), result(2, 0, A, B, C), result(3, 0, A, B, C)));
    assertEquals(List.of(1, 1, 1), List.of(scores.get(1L).place(), scores.get(2L).place(), scores.get(3L).place()));
    assertEquals(List.of(3, 3, 3), List.of(scores.get(1L).bonus(), scores.get(2L).bonus(), scores.get(3L).bonus()));
  }

  @Test
  void zeroSolvedTeamsStillHoldAPlaceButNeverGetABonus() {
    Map<Long, Score> scores = ContestScoring.scoreRound(List.of(result(1, 0), result(2, 30), result(3, 12, A)));
    assertEquals(1, scores.get(3L).place());
    assertEquals(3, scores.get(3L).bonus());
    assertEquals(2, scores.get(1L).place(), "0 solved with the lower penalty");
    assertEquals(0, scores.get(1L).bonus(), "a place in the top three still gives nothing for 0 solved");
    assertEquals(3, scores.get(2L).place());
    assertEquals(0, scores.get(2L).bonus());
    assertEquals(0, scores.get(1L).total());
    assertEquals(2 + 3, scores.get(3L).total());
  }

  @Test
  void onlyTeamsPassedInArePlacedAndAnEmptyRoundIsEmpty() {
    assertTrue(ContestScoring.scoreRound(List.of()).isEmpty());
    Map<Long, Score> scores = ContestScoring.scoreRound(List.of(result(7, 100, A)));
    assertEquals(1, scores.size());
    assertEquals(1, scores.get(7L).place());
    assertEquals(3, scores.get(7L).bonus());
    assertEquals(5, scores.get(7L).total());
  }

  @Test
  void aPerfectFirstPlaceTeamScoresFifteenOutOfFifteen() {
    Score score = ContestScoring.scoreRound(List.of(result(1, 99, A, B, C, D, E))).get(1L);
    assertEquals(12, score.problemPoints());
    assertEquals(3, score.bonus());
    assertEquals(15, score.total());
    assertEquals(ContestScoring.MAX_TOTAL, score.total());
    assertEquals(5, score.solvedCount());
  }

  @Test
  void theOrderTheTeamsArePassedInDoesNotChangeAnything() {
    List<Result> input = List.of(result(1, 10, A, B, C), result(2, 10, A, B, C), result(3, 70, A, B), result(4, 5, A, B, C, D));
    Map<Long, Score> forward = ContestScoring.scoreRound(input);
    Map<Long, Score> backward = ContestScoring.scoreRound(input.reversed());
    assertEquals(forward, backward);
  }

  // ---- eligibility -------------------------------------------------------------------------------

  @Test
  void eligibleFromExactlyThreeSolved() {
    assertFalse(ContestScoring.eligible(0));
    assertFalse(ContestScoring.eligible(2));
    assertTrue(ContestScoring.eligible(3));
    assertTrue(ContestScoring.eligible(5));
    assertEquals(3, ContestScoring.ELIGIBLE_SOLVED);
  }

  @Test
  void eligibilityCountsProblemsNotPointsOrPlace() {
    Map<Long, Score> scores = ContestScoring.scoreRound(
      List.of(result(1, 0, B, C), result(2, 500, A, D, E), result(3, 0, A, B, C, D))
    );
    assertFalse(scores.get(1L).eligible(), "B + C is 6 points but only two problems");
    assertTrue(scores.get(2L).eligible(), "A + D + E is 6 points and exactly three problems, with a high penalty");
    assertTrue(scores.get(3L).eligible());
    assertEquals(3, scores.get(2L).solvedCount());
    assertEquals(2, scores.get(2L).place(), "behind the four-problem team, ahead of the two-problem team");
  }

  // ---- input guards ------------------------------------------------------------------------------

  @Test
  void aNegativePenaltyIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> new Result(1, solved(A), -1));
  }

  @Test
  void aResultKeepsItsOwnCopyOfTheSolvedSet() {
    Set<ContestProblem> source = solved(A, B);
    Result result = new Result(1, source, 0);
    source.add(C);
    assertEquals(2, result.solved().size());
  }
}
