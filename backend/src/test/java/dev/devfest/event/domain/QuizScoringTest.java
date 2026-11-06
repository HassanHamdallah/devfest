package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuizScoringTest {

  private static BigDecimal points(String value) {
    return new BigDecimal(value);
  }

  @Test
  void aCorrectOptionEarnsTheQuestionsPointsAndAnythingElseNothing() {
    assertEquals(points("3.00"), QuizScoring.mcqPoints(3, true));
    assertEquals(points("0.00"), QuizScoring.mcqPoints(3, false));
  }

  @Test
  void allCorrectNoneCorrectAndPartialOrdersOfFourFiveAndSixSnippets() {
    List<Long> four = List.of(11L, 12L, 13L, 14L);
    assertEquals(4, QuizScoring.correctPositions(four, four));
    assertEquals(points("10.00"), QuizScoring.repairPoints(10, 4, 4));
    assertEquals(0, QuizScoring.correctPositions(List.of(12L, 13L, 14L, 11L), four), "rotated: nothing in place");
    assertEquals(points("0.00"), QuizScoring.repairPoints(10, 0, 4));
    assertEquals(2, QuizScoring.correctPositions(List.of(11L, 13L, 12L, 14L), four), "two swapped in the middle");
    assertEquals(points("5.00"), QuizScoring.repairPoints(10, 2, 4));

    List<Long> five = List.of(1L, 2L, 3L, 4L, 5L);
    assertEquals(3, QuizScoring.correctPositions(List.of(1L, 2L, 3L, 5L, 4L), five));
    assertEquals(points("6.00"), QuizScoring.repairPoints(10, 3, 5));

    List<Long> six = List.of(1L, 2L, 3L, 4L, 5L, 6L);
    assertEquals(1, QuizScoring.correctPositions(List.of(1L, 3L, 2L, 5L, 6L, 4L), six));
    assertEquals(points("1.67"), QuizScoring.repairPoints(10, 1, 6));
  }

  @Test
  void scoresRoundHalfUpToTwoDecimals() {
    assertEquals(points("3.33"), QuizScoring.repairPoints(10, 2, 6));
    assertEquals(points("6.67"), QuizScoring.repairPoints(10, 4, 6));
    assertEquals(points("8.33"), QuizScoring.repairPoints(10, 5, 6));
    assertEquals(points("0.63"), QuizScoring.repairPoints(1, 5, 8), "5/8 = 0.625 rounds up");
    assertEquals(points("11.67"), QuizScoring.total(List.of(points("1.67"), points("10.00"))));
  }

  @Test
  void aSubmissionMustOrderEverySnippetAndCountsMustBeConsistent() {
    assertThrows(IllegalArgumentException.class, () -> QuizScoring.correctPositions(List.of(1L, 2L), List.of(1L, 2L, 3L)));
    assertThrows(IllegalArgumentException.class, () -> QuizScoring.repairPoints(10, 5, 4));
    assertThrows(IllegalArgumentException.class, () -> QuizScoring.repairPoints(10, 0, 0));
  }

  @Test
  void aRoomTotalIsMcqPlusRepairsAndMaximumOneHundred() {
    // 20×1 + 15×2 + 6×3 + 3×2 + 1×2 = 76 for the MCQs and 3 repairs of 8 points.
    List<BigDecimal> items = new ArrayList<>();
    for (int i = 0; i < 20; i++) items.add(QuizScoring.mcqPoints(1, true));
    for (int i = 0; i < 15; i++) items.add(QuizScoring.mcqPoints(2, true));
    for (int i = 0; i < 6; i++) items.add(QuizScoring.mcqPoints(3, true));
    for (int i = 0; i < 3; i++) items.add(QuizScoring.mcqPoints(2, true));
    items.add(QuizScoring.mcqPoints(2, true));
    items.add(QuizScoring.repairPoints(8, 4, 4));
    items.add(QuizScoring.repairPoints(8, 4, 4));
    items.add(QuizScoring.repairPoints(8, 6, 6));
    assertEquals(points("100.00"), QuizScoring.total(items));
  }

  @Test
  void aTotalEqualToThePassScorePassesAndAnythingBelowFails() {
    assertTrue(QuizScoring.passes(points("60.00"), 60), "equal passes");
    assertTrue(QuizScoring.passes(points("60.01"), 60));
    assertFalse(QuizScoring.passes(points("59.99"), 60), "a hundredth below fails");
    assertTrue(QuizScoring.passes(points("0.00"), 0), "pass score 0: everyone passes");
    assertFalse(QuizScoring.passes(points("99.99"), 100));
  }
}
