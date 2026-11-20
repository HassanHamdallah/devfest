package dev.devfest.event.domain.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.EventRuleException;
import dev.devfest.event.domain.recovery.MlChecks.CleaningChoice;
import dev.devfest.event.domain.recovery.MlChecks.Features;
import dev.devfest.event.domain.recovery.MlChecks.ModelPick;
import dev.devfest.event.domain.recovery.MlChecks.Order;
import dev.devfest.event.domain.recovery.MlChecks.ThresholdValue;
import dev.devfest.event.domain.recovery.MlChecks.Word;
import dev.devfest.event.domain.recovery.RecoveryLevels.Ml;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every ML stage of the shipped content: the right answer passes, every other well-formed answer fails, a malformed one is refused. */
class MlChecksTest {

  private static final Ml ML = ShippedRecovery.levels().ml();

  @Test
  void stage1KeepsEveryFeatureButTheInvestigationResultInAnyOrder() {
    assertTrue(MlChecks.passes(ML, 1, new Features(List.of("motion_score", "access_attempts", "signal_strength", "device_type"))));
    assertTrue(MlChecks.passes(ML, 1, new Features(List.of("device_type", "signal_strength", "motion_score", "access_attempts"))));
    assertFalse(MlChecks.passes(ML, 1, new Features(List.of("motion_score", "access_attempts", "signal_strength", "device_type", "investigation_result"))));
    assertFalse(MlChecks.passes(ML, 1, new Features(List.of("motion_score", "access_attempts", "signal_strength"))), "a useful feature removed");
    assertFalse(MlChecks.passes(ML, 1, new Features(List.of())));
    assertThrows(EventRuleException.class, () -> MlChecks.passes(ML, 1, new Features(List.of("motion_score", "motion_score"))));
    assertThrows(EventRuleException.class, () -> MlChecks.passes(ML, 1, new Features(List.of("label"))));
    assertThrows(EventRuleException.class, () -> MlChecks.passes(ML, 1, new Features(null)));
  }

  @Test
  void stage2SplitsBeforeFittingTheScaler() {
    assertTrue(MlChecks.passes(ML, 2, new Order(List.of("load", "split", "fit_scaler", "transform", "train", "evaluate"))));
    assertFalse(MlChecks.passes(ML, 2, new Order(List.of("load", "fit_scaler", "split", "transform", "train", "evaluate"))), "the start order");
    assertFalse(MlChecks.passes(ML, 2, new Order(List.of("load", "split", "transform", "fit_scaler", "train", "evaluate"))));
    assertFalse(MlChecks.passes(ML, 2, new Order(List.of("split", "load", "fit_scaler", "transform", "train", "evaluate"))));
    assertThrows(EventRuleException.class, () -> MlChecks.passes(ML, 2, new Order(List.of("load", "split", "fit_scaler", "transform", "train"))));
    assertThrows(EventRuleException.class, () -> MlChecks.passes(ML, 2, new Order(List.of("load", "load", "fit_scaler", "transform", "train", "evaluate"))));
    assertThrows(EventRuleException.class, () -> MlChecks.passes(ML, 2, new Order(Arrays.asList("load", null, "fit_scaler", "transform", "train", "evaluate"))));
  }

  @Test
  void stage3ImputesTheTrainingMedianAndFlagsOutliers() {
    for (String missing : List.of("zero", "drop", "median")) {
      for (String outliers : List.of("ignore", "mean", "flag")) {
        boolean right = missing.equals("median") && outliers.equals("flag");
        assertTrue(MlChecks.passes(ML, 3, new CleaningChoice(missing, outliers)) == right, missing + "/" + outliers);
      }
    }
    assertThrows(EventRuleException.class, () -> MlChecks.passes(ML, 3, new CleaningChoice("mean", "flag")));
    assertThrows(EventRuleException.class, () -> MlChecks.passes(ML, 3, new CleaningChoice("median", null)));
  }

  @Test
  void stage4PicksTheOnlyModelMeetingBothRequirements() {
    assertTrue(MlChecks.passes(ML, 4, new ModelPick("logreg")));
    assertFalse(MlChecks.passes(ML, 4, new ModelPick("knn")), "precision ok, recall 0.71");
    assertFalse(MlChecks.passes(ML, 4, new ModelPick("tree")), "recall ok, precision 0.68");
    assertThrows(EventRuleException.class, () -> MlChecks.passes(ML, 4, new ModelPick("svm")));
  }

  @Test
  void stage5PassesOnlyFromPointFortyTwoToPointFiftyThree() {
    assertFalse(MlChecks.passes(ML, 5, new ThresholdValue(new BigDecimal("0.41"))));
    assertTrue(MlChecks.passes(ML, 5, new ThresholdValue(new BigDecimal("0.42"))));
    assertTrue(MlChecks.passes(ML, 5, new ThresholdValue(new BigDecimal("0.53"))));
    assertFalse(MlChecks.passes(ML, 5, new ThresholdValue(new BigDecimal("0.54"))));
    assertThrows(EventRuleException.class, () -> MlChecks.passes(ML, 5, new ThresholdValue(new BigDecimal("0.24"))), "below the slider");
    assertThrows(EventRuleException.class, () -> MlChecks.passes(ML, 5, new ThresholdValue(new BigDecimal("0.81"))), "above the slider");
    assertThrows(EventRuleException.class, () -> MlChecks.passes(ML, 5, new ThresholdValue(null)));
  }

  @Test
  void stage6TakesTheWordTrimmedAndInAnyCase() {
    assertTrue(MlChecks.passes(ML, 6, new Word("CLUE")));
    assertTrue(MlChecks.passes(ML, 6, new Word("  clue \n")));
    assertTrue(MlChecks.passes(ML, 6, new Word("Clue")));
    assertFalse(MlChecks.passes(ML, 6, new Word("ULCE")), "the records in their listed order");
    assertFalse(MlChecks.passes(ML, 6, new Word("C L U E")));
    assertFalse(MlChecks.passes(ML, 6, new Word("")));
    assertThrows(EventRuleException.class, () -> MlChecks.passes(ML, 6, new Word("x".repeat(101))));
  }

  @Test
  void anAnswerOfAnotherStageIsRefused() {
    assertThrows(EventRuleException.class, () -> MlChecks.passes(ML, 1, new Word("CLUE")));
    assertThrows(EventRuleException.class, () -> MlChecks.passes(ML, 7, new Word("CLUE")));
  }
}
