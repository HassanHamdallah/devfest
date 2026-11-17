package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.devfest.event.domain.FinalScoring.Input;
import dev.devfest.event.domain.FinalScoring.Score;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The final's 40 points: 3.75 per decrypted evidence file (max 15), suspect 10, time max 15. */
class FinalScoringTest {

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }

  private static Score score(int clues, Integer activeSeconds, boolean suspectCorrect) {
    return FinalScoring.score(new Input(clues, activeSeconds, suspectCorrect, 25));
  }

  @Test
  void aPerfectFinalScoresForty() {
    Score s = score(4, 10 * 60, true);
    assertEquals(d("15.00"), s.clues());
    assertEquals(d("10.00"), s.suspect());
    assertEquals(d("15.00"), s.time());
    assertEquals(d("40.00"), s.total());
  }

  @Test
  void eachDecryptedFileIsWorthThreePointSevenFive() {
    assertEquals(d("0.00"), score(0, null, false).clues());
    assertEquals(d("3.75"), score(1, null, false).clues());
    assertEquals(d("7.50"), score(2, null, false).clues());
    assertEquals(d("11.25"), score(3, null, false).clues());
    assertEquals(d("15.00"), score(4, null, false).clues());
    assertEquals(d("11.25"), score(3, null, false).total(), "nothing else without a suspect");
  }

  @Test
  void solvedCluesOutsideZeroToFourAreRefused() {
    assertThrows(IllegalArgumentException.class, () -> score(5, null, false));
    assertThrows(IllegalArgumentException.class, () -> score(-1, null, false));
  }

  @Test
  void theSuspectIsWorthTenOnlyWhenCorrectAndSubmitted() {
    assertEquals(d("10.00"), score(0, 600, true).suspect(), "the suspect does not need the files");
    assertEquals(d("0.00"), score(4, 600, false).suspect());
    assertEquals(d("0.00"), score(4, null, true).suspect());
  }

  @Test
  void timePointsAtTheBoundaries() {
    assertEquals(d("15.00"), FinalScoring.timePoints(0, 25));
    assertEquals(d("15.00"), FinalScoring.timePoints(25 * 60, 25));
    assertEquals(d("10.00"), FinalScoring.timePoints(30 * 60, 25));
    assertEquals(d("5.00"), FinalScoring.timePoints(35 * 60, 25));
    assertEquals(d("0.00"), FinalScoring.timePoints(40 * 60, 25));
    assertEquals(d("0.00"), FinalScoring.timePoints(55 * 60, 25));
  }

  @Test
  void timeIsExactToTheSecondAndRoundedToTwoDecimals() {
    // 25 min 1 s: 40 - 25.016666... = 14.983333... -> 14.98
    assertEquals(d("14.98"), FinalScoring.timePoints(25 * 60 + 1, 25));
    // 29 min 59 s: 40 - 29.983333... = 10.016666... -> 10.02
    assertEquals(d("10.02"), FinalScoring.timePoints(29 * 60 + 59, 25));
  }

  @Test
  void aShorterFullUntilNeverGivesMoreThanFifteen() {
    assertEquals(d("15.00"), FinalScoring.timePoints(20 * 60, 20));
    assertEquals(d("15.00"), FinalScoring.timePoints(21 * 60, 20));
    assertEquals(d("14.00"), FinalScoring.timePoints(26 * 60, 20));
  }

  @Test
  void timePointsNeedAllFourCluesAndASubmittedSuspect() {
    for (int clues = 0; clues <= 3; clues++) assertEquals(d("0.00"), score(clues, 300, true).time(), clues + " clues");
    assertEquals(d("0.00"), score(4, null, false).time(), "no suspect submitted");
    assertEquals(d("15.00"), score(4, 300, false).time(), "a wrong suspect still counts as submitted");
    assertEquals(d("21.25"), score(3, 300, true).total(), "3 files + suspect, no time points");
  }

  @Test
  void aWrongSuspectStillEarnsCluesAndTime() {
    assertEquals(d("30.00"), score(4, 20 * 60, false).total());
    assertEquals(d("25.00"), score(4, 30 * 60, false).total(), "30 active minutes: 10 time points");
  }
}
