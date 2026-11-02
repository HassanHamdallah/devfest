package dev.devfest.event.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * AI room scores (rules §19.5), always computed by the server. Scoring compares option and snippet ids, never
 * positions on a team's screen. Results have two decimals, like the NUMERIC(6,2) columns that store them.
 */
public final class QuizScoring {

  private static final int SCALE = 2;

  private QuizScoring() {}

  /** A correct option earns the question's points, anything else 0. */
  public static BigDecimal mcqPoints(int points, boolean correctOption) {
    return correctOption ? scaled(BigDecimal.valueOf(points)) : scaled(BigDecimal.ZERO);
  }

  /**
   * How many snippets the team put at their correct place. {@code submitted} and {@code correctOrder} are snippet
   * ids; position i of the submission is compared with position i of the correct order.
   */
  public static int correctPositions(List<Long> submitted, List<Long> correctOrder) {
    if (submitted.size() != correctOrder.size()) {
      throw new IllegalArgumentException("A submission must order every snippet exactly once.");
    }
    int correct = 0;
    for (int i = 0; i < submitted.size(); i++) {
      if (Objects.equals(submitted.get(i), correctOrder.get(i))) correct++;
    }
    return correct;
  }

  /** points × (snippets in their correct position ÷ snippets), rounded half up to 2 decimals. */
  public static BigDecimal repairPoints(int points, int correctPositions, int snippetCount) {
    if (snippetCount <= 0 || correctPositions < 0 || correctPositions > snippetCount) {
      throw new IllegalArgumentException("0 <= correctPositions <= snippetCount, snippetCount > 0");
    }
    return BigDecimal.valueOf((long) points * correctPositions).divide(BigDecimal.valueOf(snippetCount), SCALE, RoundingMode.HALF_UP);
  }

  /** The sum of already rounded item scores (MCQs plus repairs). */
  public static BigDecimal total(List<BigDecimal> itemScores) {
    return scaled(itemScores.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  /** A team passes the room with a total of at least the pass score (equal passes). */
  public static boolean passes(BigDecimal total, int passScore) {
    return total.compareTo(BigDecimal.valueOf(passScore)) >= 0;
  }

  private static BigDecimal scaled(BigDecimal value) {
    return value.setScale(SCALE, RoundingMode.HALF_UP);
  }
}
