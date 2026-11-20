package dev.devfest.event.domain.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.recovery.RecoveryLevels.ThresholdRequirement;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ML stage 5's metrics, exact in decimal, and the requirement R >= 0.90, P >= 0.75, FN <= 2. */
class ThresholdMetricsTest {

  private static final ThresholdRequirement REQUIREMENT = new ThresholdRequirement(new BigDecimal("0.90"), new BigDecimal("0.75"), 2);

  private static boolean passes(String t) {
    return ThresholdMetrics.passes(new BigDecimal(t), REQUIREMENT);
  }

  @Test
  void onlyThresholdsFromPointFortyTwoToPointFiftyThreePass() {
    assertFalse(passes("0.41"), "precision 0.7486 < 0.75");
    assertTrue(passes("0.42"), "precision 0.7532");
    assertTrue(passes("0.53"), "recall 0.9016");
    assertFalse(passes("0.54"), "recall 0.8988 < 0.90");
    assertFalse(passes("0.25"));
    assertFalse(passes("0.80"));
  }

  @Test
  void theSentValueIsRoundedToTwoDecimalsFirst() {
    assertTrue(passes("0.415"), "rounds half up to 0.42");
    assertFalse(passes("0.4149"), "rounds to 0.41");
    assertTrue(passes("0.534"), "rounds to 0.53");
    assertFalse(passes("0.535"), "rounds to 0.54");
  }

  @Test
  void theMetricsFollowTheFormulasAndClamp() {
    ThresholdMetrics.Metrics half = ThresholdMetrics.at(new BigDecimal("0.50"));
    assertEquals(new BigDecimal("0.79"), half.precision());
    assertEquals(new BigDecimal("0.91"), half.recall());
    assertEquals(2, half.falseNegatives(), "round(0.09 x 20 = 1.8)");
    assertEquals(new BigDecimal("0.85"), half.f1(), "2 x 0.79 x 0.91 / 1.70 = 0.8458");
    assertEquals(new BigDecimal("0.97"), ThresholdMetrics.recall(new BigDecimal("0.25")), "1.05 - 0.07 = 0.98 clamps to 0.97");
    assertEquals(new BigDecimal("0.60"), ThresholdMetrics.recall(new BigDecimal("2")).setScale(2), "clamped below");
    assertEquals(new BigDecimal("0.92"), ThresholdMetrics.precision(new BigDecimal("0.80")), "0.928 clamps to 0.92");
    assertEquals(1, ThresholdMetrics.falseNegatives(new BigDecimal("0.25")), "round(0.03 x 20 = 0.6)");
    assertEquals(2, ThresholdMetrics.falseNegatives(new BigDecimal("0.60")), "recall 0.882, round(2.36)");
    assertEquals(3, ThresholdMetrics.falseNegatives(new BigDecimal("0.70")), "recall 0.854, round(2.92)");
  }

  @Test
  void theSliderListsEveryValueFromMinToMax() {
    List<BigDecimal> values = ThresholdMetrics.sliderValues(
      new RecoveryLevels.Slider(new BigDecimal("0.25"), new BigDecimal("0.80"), new BigDecimal("0.01"), new BigDecimal("0.50"))
    );
    assertEquals(56, values.size());
    assertEquals(0, new BigDecimal("0.25").compareTo(values.getFirst()));
    assertEquals(0, new BigDecimal("0.80").compareTo(values.getLast()));
    assertEquals(12, values.stream().filter(t -> ThresholdMetrics.passes(t, REQUIREMENT)).count(), "0.42 to 0.53");
  }
}
