package dev.devfest.event.domain.recovery;

import dev.devfest.event.domain.EventRuleException;
import dev.devfest.event.domain.recovery.RecoveryLevels.Cleaning;
import dev.devfest.event.domain.recovery.RecoveryLevels.Evidence;
import dev.devfest.event.domain.recovery.RecoveryLevels.Item;
import dev.devfest.event.domain.recovery.RecoveryLevels.Leakage;
import dev.devfest.event.domain.recovery.RecoveryLevels.Ml;
import dev.devfest.event.domain.recovery.RecoveryLevels.Model;
import dev.devfest.event.domain.recovery.RecoveryLevels.ModelChoice;
import dev.devfest.event.domain.recovery.RecoveryLevels.Option;
import dev.devfest.event.domain.recovery.RecoveryLevels.Pipeline;
import dev.devfest.event.domain.recovery.RecoveryLevels.Threshold;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The answer check of each ML stage. A malformed answer (an unknown id, a step missing, a value off the slider) is
 * refused with {@link EventRuleException} before it is checked; a well-formed answer is simply right or wrong. Pure.
 */
public final class MlChecks {

  /** The longest word accepted by stage 6. */
  public static final int MAX_WORD = 100;

  /** One stage's answer, as the team sends it. */
  public sealed interface Answer {}

  /** Stage 1: the features the team keeps. */
  public record Features(List<String> kept) implements Answer {}

  /** Stage 2: every pipeline step id, in the team's order. */
  public record Order(List<String> steps) implements Answer {}

  /** Stage 3: the chosen option for missing values and for outliers. */
  public record CleaningChoice(String missing, String outliers) implements Answer {}

  /** Stage 4: the chosen model's id. */
  public record ModelPick(String model) implements Answer {}

  /** Stage 5: the slider value. */
  public record ThresholdValue(BigDecimal threshold) implements Answer {}

  /** Stage 6: the recovered word. */
  public record Word(String word) implements Answer {}

  private MlChecks() {}

  /** True when {@code answer} passes stage {@code stage} (1..6) of {@code ml}; throws when it is malformed. */
  public static boolean passes(Ml ml, int stage, Answer answer) {
    return switch (stage) {
      case 1 -> leakage(ml.leakage(), as(answer, Features.class));
      case 2 -> pipeline(ml.pipeline(), as(answer, Order.class));
      case 3 -> cleaning(ml.cleaning(), as(answer, CleaningChoice.class));
      case 4 -> model(ml.model(), as(answer, ModelPick.class));
      case 5 -> threshold(ml.threshold(), as(answer, ThresholdValue.class));
      case 6 -> evidence(ml.evidence(), as(answer, Word.class));
      default -> throw new EventRuleException("The ML level has stages 1 to 6.");
    };
  }

  static boolean leakage(Leakage stage, Features answer) {
    List<String> kept = require(answer.kept(), "Send the features you keep.");
    Set<String> unique = new HashSet<>(kept);
    if (unique.size() != kept.size() || !stage.features().containsAll(unique)) {
      throw new EventRuleException("Keep only listed features, each at most once.");
    }
    return unique.equals(Set.copyOf(stage.kept()));
  }

  static boolean pipeline(Pipeline stage, Order answer) {
    List<String> steps = require(answer.steps(), "Send every pipeline step in your order.");
    Set<String> ids = new HashSet<>(stage.items().stream().map(Item::id).toList());
    if (steps.size() != ids.size() || !ids.equals(new HashSet<>(steps))) {
      throw new EventRuleException("Send every pipeline step exactly once.");
    }
    return steps.equals(stage.order());
  }

  static boolean cleaning(Cleaning stage, CleaningChoice answer) {
    String missing = require(answer.missing(), "Choose how to handle missing values.");
    String outliers = require(answer.outliers(), "Choose how to handle outliers.");
    if (!optionIds(stage.missing().options()).contains(missing) || !optionIds(stage.outliers().options()).contains(outliers)) {
      throw new EventRuleException("Choose one of the listed cleaning options.");
    }
    return missing.equals(stage.answer().missing()) && outliers.equals(stage.answer().outliers());
  }

  static boolean model(ModelChoice stage, ModelPick answer) {
    String model = require(answer.model(), "Choose a model.");
    if (stage.models().stream().map(Model::id).noneMatch(model::equals)) throw new EventRuleException("Choose one of the listed models.");
    return model.equals(stage.answer());
  }

  static boolean threshold(Threshold stage, ThresholdValue answer) {
    BigDecimal t = ThresholdMetrics.round(require(answer.threshold(), "Send a threshold."));
    if (t.compareTo(stage.slider().min()) < 0 || t.compareTo(stage.slider().max()) > 0) {
      throw new EventRuleException("The threshold must be on the slider (" + stage.slider().min() + " to " + stage.slider().max() + ").");
    }
    return ThresholdMetrics.passes(t, stage.requirement());
  }

  static boolean evidence(Evidence stage, Word answer) {
    String word = require(answer.word(), "Enter the recovered word.");
    if (word.length() > MAX_WORD) throw new EventRuleException("The word is at most " + MAX_WORD + " characters.");
    return word.strip().toUpperCase(Locale.ROOT).equals(stage.answer().strip().toUpperCase(Locale.ROOT));
  }

  private static Set<String> optionIds(List<Option> options) {
    return Set.copyOf(options.stream().map(Option::id).toList());
  }

  private static <T extends Answer> T as(Answer answer, Class<T> type) {
    if (!type.isInstance(answer)) throw new EventRuleException("This answer does not fit the stage.");
    return type.cast(answer);
  }

  private static <T> T require(T value, String message) {
    if (value == null) throw new EventRuleException(message);
    if (value instanceof List<?> list && list.stream().anyMatch(java.util.Objects::isNull)) throw new EventRuleException(message);
    return value;
  }
}
