package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.devfest.event.domain.FinalScoring.Input;
import dev.devfest.event.domain.FinalScoring.Score;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The final's 40 points: layers and verdicts 2.5 each (max 15), suspect 10, time max 15. */
class FinalScoringTest {

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }

  private static Score score(int layers, int verdicts, Integer activeSeconds, boolean suspectCorrect) {
    return FinalScoring.score(new Input(layers, verdicts, activeSeconds, suspectCorrect, 25));
  }

  @Test
  void aPerfectFinalScoresForty() {
    Score s = score(4, 2, 10 * 60, true);
    assertEquals(d("15.00"), s.crypto());
    assertEquals(d("10.00"), s.suspect());
    assertEquals(d("15.00"), s.time());
    assertEquals(d("40.00"), s.total());
  }

  @Test
  void cryptoCountsEachCorrectLayerAndVerdict() {
    assertEquals(d("2.50"), score(1, 0, null, false).crypto());
    assertEquals(d("7.50"), score(2, 1, null, false).crypto());
    assertEquals(d("12.50"), score(4, 1, null, false).crypto());
    assertEquals(d("15.00"), score(4, 2, null, false).crypto());
  }

  @Test
  void theSuspectIsWorthTenOnlyWhenCorrectAndSubmitted() {
    assertEquals(d("10.00"), score(4, 0, 600, true).suspect());
    assertEquals(d("0.00"), score(4, 0, 600, false).suspect());
    assertEquals(d("0.00"), score(4, 0, null, true).suspect());
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
  void noTimePointsWithoutAllFourLayersAndASubmittedSuspect() {
    assertEquals(d("0.00"), score(3, 2, 300, true).time());
    assertEquals(d("0.00"), score(0, 0, 300, true).time());
    assertEquals(d("0.00"), score(4, 2, null, false).time());
    assertEquals(d("15.00"), score(4, 2, 300, false).time());
  }

  @Test
  void aWrongSuspectStillEarnsCryptoAndTime() {
    assertEquals(d("30.00"), score(4, 2, 20 * 60, false).total());
  }
}
