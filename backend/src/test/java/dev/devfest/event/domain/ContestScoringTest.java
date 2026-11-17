package dev.devfest.event.domain;

import static dev.devfest.event.domain.ContestProblem.A;
import static dev.devfest.event.domain.ContestProblem.B;
import static dev.devfest.event.domain.ContestProblem.C;
import static dev.devfest.event.domain.ContestProblem.D;
import static dev.devfest.event.domain.ContestProblem.E;
import static dev.devfest.event.domain.ContestProblem.F;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.ContestScoring.Score;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContestScoringTest {

  private static Set<ContestProblem> solved(ContestProblem... problems) {
    return problems.length == 0 ? EnumSet.noneOf(ContestProblem.class) : EnumSet.of(problems[0], problems);
  }

  private static Set<ContestProblem> first(int count) {
    Set<ContestProblem> set = EnumSet.noneOf(ContestProblem.class);
    for (ContestProblem problem : ContestProblem.values()) {
      if (set.size() < count) set.add(problem);
    }
    return set;
  }

  // ---- problem points ----------------------------------------------------------------------------

  @Test
  void everyOfTheSixProblemsIsWorthTwoAndAllSixMakeTwelve() {
    assertEquals(6, ContestProblem.values().length);
    assertEquals(6, ContestScoring.PROBLEM_COUNT);
    for (ContestProblem problem : ContestProblem.values()) assertEquals(2, problem.points(), problem.name());
    assertEquals(12, ContestScoring.MAX_PROBLEM_POINTS);
    assertEquals(3, ContestScoring.MAX_BONUS);
    assertEquals(15, ContestScoring.MAX_TOTAL);
    assertEquals(12, ContestScoring.problemPoints(solved(A, B, C, D, E, F)));
  }

  @Test
  void problemPointsAreTwoTimesTheNumberSolvedFromZeroToSix() {
    for (int count = 0; count <= 6; count++) {
      assertEquals(2 * count, ContestScoring.problemPoints(first(count)), count + " solved");
    }
    assertEquals(4, ContestScoring.problemPoints(solved(A, F)), "problem F counts like any other");
    assertEquals(2, ContestScoring.problemPoints(solved(F)));
  }

  // ---- the bonus from the chosen standing --------------------------------------------------------

  @Test
  void theBonusIsThreeTwoOneThenZeroForTheFourStandings() {
    assertEquals(3, ContestScoring.bonus(ContestStanding.FIRST, 4));
    assertEquals(2, ContestScoring.bonus(ContestStanding.SECOND, 4));
    assertEquals(1, ContestScoring.bonus(ContestStanding.THIRD, 4));
    assertEquals(0, ContestScoring.bonus(ContestStanding.OTHER, 4));
  }

  @Test
  void aTeamThatSolvedNothingGetsNoBonusWhateverStandingWasPicked() {
    for (ContestStanding standing : ContestStanding.values()) {
      assertEquals(0, ContestScoring.bonus(standing, 0), standing.name());
      Score score = ContestScoring.score(solved(), standing);
      assertEquals(0, score.bonus());
      assertEquals(0, score.total());
      assertFalse(score.eligible());
    }
  }

  @Test
  void theStandingsHaveTheLabelsPeopleRead() {
    assertEquals("1st", ContestStanding.FIRST.label());
    assertEquals("2nd", ContestStanding.SECOND.label());
    assertEquals("3rd", ContestStanding.THIRD.label());
    assertEquals("Other", ContestStanding.OTHER.label());
  }

  // ---- the total ---------------------------------------------------------------------------------

  @Test
  void totalIsProblemPointsPlusBonusForEveryStandingAndEveryCount() {
    for (int count = 1; count <= 6; count++) {
      for (ContestStanding standing : ContestStanding.values()) {
        Score score = ContestScoring.score(first(count), standing);
        assertEquals(count, score.solvedCount());
        assertEquals(2 * count, score.problemPoints());
        assertEquals(standing.bonus(), score.bonus());
        assertEquals(2 * count + standing.bonus(), score.total());
        assertEquals(standing, score.standing());
      }
    }
  }

  @Test
  void aTieIsAllowedTwoTeamsMayBothBeFirstAndBothGetThree() {
    Score one = ContestScoring.score(solved(A, B, C), ContestStanding.FIRST);
    Score two = ContestScoring.score(solved(D, E, F), ContestStanding.FIRST);
    assertEquals(3, one.bonus());
    assertEquals(3, two.bonus());
    assertEquals(9, one.total());
    assertEquals(9, two.total());
  }

  @Test
  void aPerfectTeamScoresFifteenAndNobodyCanScoreMore() {
    Score score = ContestScoring.score(solved(A, B, C, D, E, F), ContestStanding.FIRST);
    assertEquals(12, score.problemPoints());
    assertEquals(3, score.bonus());
    assertEquals(15, score.total());
    assertEquals(ContestScoring.MAX_TOTAL, score.total());
    for (int count = 0; count <= 6; count++) {
      for (ContestStanding standing : ContestStanding.values()) {
        assertTrue(ContestScoring.score(first(count), standing).total() <= 15);
      }
    }
  }

  @Test
  void noStandingYetCountsAsOther() {
    Score score = ContestScoring.score(solved(A, B, C, D), null);
    assertEquals(ContestStanding.OTHER, score.standing());
    assertEquals(0, score.bonus());
    assertEquals(8, score.total());
  }

  // ---- eligibility -------------------------------------------------------------------------------

  @Test
  void eligibleFromExactlyThreeSolved() {
    assertFalse(ContestScoring.eligible(0));
    assertFalse(ContestScoring.eligible(2));
    assertTrue(ContestScoring.eligible(3));
    assertTrue(ContestScoring.eligible(6));
    assertEquals(3, ContestScoring.ELIGIBLE_SOLVED);
  }

  @Test
  void eligibilityCountsProblemsNotTheStanding() {
    assertFalse(ContestScoring.score(solved(A, F), ContestStanding.FIRST).eligible(), "1st with two problems is not enough");
    assertTrue(ContestScoring.score(solved(A, D, F), ContestStanding.OTHER).eligible(), "Other with three problems is");
  }
}
