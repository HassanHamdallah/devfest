package dev.devfest.event.domain.recovery;

import dev.devfest.event.domain.recovery.RecoveryLevels.Slider;
import dev.devfest.event.domain.recovery.RecoveryLevels.ThresholdRequirement;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The detector's metrics at a threshold t (ML stage 5), exact in decimal: recall r = clamp(1.05 - 0.28 t, 0.60, 0.97),
 * precision p = clamp(0.56 + 0.46 t, 0.55, 0.92), false negatives fn = round((1 - r) * 20) (half up), F1 = 2pr / (p + r).
 * The submitted t is first rounded to 2 decimals. Pure.
 */
public final class ThresholdMetrics {

  /** The metrics shown to the team: precision, recall and F1 rounded to 2 decimals, and the false negatives. */
  public record Metrics(BigDecimal threshold, BigDecimal precision, BigDecimal recall, BigDecimal f1, int falseNegatives) {}

  private static final BigDecimal R_BASE = new BigDecimal("1.05");
  private static final BigDecimal R_SLOPE = new BigDecimal("0.28");
  private static final BigDecimal R_MIN = new BigDecimal("0.60");
  private static final BigDecimal R_MAX = new BigDecimal("0.97");
  private static final BigDecimal P_BASE = new BigDecimal("0.56");
  private static final BigDecimal P_SLOPE = new BigDecimal("0.46");
  private static final BigDecimal P_MIN = new BigDecimal("0.55");
  private static final BigDecimal P_MAX = new BigDecimal("0.92");
  private static final BigDecimal SAMPLES = BigDecimal.valueOf(20);
  private static final BigDecimal TWO = BigDecimal.valueOf(2);

  private ThresholdMetrics() {}

  /** {@code threshold} rounded to the slider's 2 decimals (half up). */
  public static BigDecimal round(BigDecimal threshold) {
    return threshold.setScale(2, RoundingMode.HALF_UP);
  }

  public static BigDecimal recall(BigDecimal t) {
    return clamp(R_BASE.subtract(R_SLOPE.multiply(round(t))), R_MIN, R_MAX);
  }

  public static BigDecimal precision(BigDecimal t) {
    return clamp(P_BASE.add(P_SLOPE.multiply(round(t))), P_MIN, P_MAX);
  }

  public static int falseNegatives(BigDecimal t) {
    return BigDecimal.ONE.subtract(recall(t)).multiply(SAMPLES).setScale(0, RoundingMode.HALF_UP).intValueExact();
  }

  public static Metrics at(BigDecimal threshold) {
    BigDecimal t = round(threshold);
    BigDecimal p = precision(t);
    BigDecimal r = recall(t);
    BigDecimal f1 = TWO.multiply(p).multiply(r).divide(p.add(r), MathContext.DECIMAL64);
    return new Metrics(t, two(p), two(r), two(f1), falseNegatives(t));
  }

  /** True when the exact metrics at {@code threshold} meet every part of the requirement. */
  public static boolean passes(BigDecimal threshold, ThresholdRequirement requirement) {
    return recall(threshold).compareTo(requirement.minRecall()) >= 0
      && precision(threshold).compareTo(requirement.minPrecision()) >= 0
      && falseNegatives(threshold) <= requirement.maxFalseNegatives();
  }

  /** Every value the slider can take, min to max by step. */
  public static List<BigDecimal> sliderValues(Slider slider) {
    List<BigDecimal> values = new ArrayList<>();
    for (BigDecimal t = slider.min(); t.compareTo(slider.max()) <= 0; t = t.add(slider.step())) values.add(t);
    return values;
  }

  private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
    return value.max(min).min(max);
  }

  private static BigDecimal two(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }
}
