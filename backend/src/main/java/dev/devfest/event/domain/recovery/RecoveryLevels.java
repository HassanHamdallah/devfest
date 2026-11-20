package dev.devfest.event.domain.recovery;

import java.math.BigDecimal;
import java.util.List;

/**
 * The two recovery levels of the final's content file ("recoveryLevels"): every text, every value and every answer.
 * Fields are boxed so a missing value is reported by {@link RecoveryContentRules} instead of failing the read. The
 * answers ({@code kept}, {@code order}, {@code answer}, the threshold requirement, the slot's {@code correct} option and
 * the bugs' accepted lines) never leave the server; the views copy only the public parts.
 */
public record RecoveryLevels(Ml ml, Arduino arduino) {
  // ---- ML: one continuous audit in six stages ------------------------------------------------------------------------

  public record Ml(
    String title,
    String intro,
    String badge,
    String tokenLabel,
    String token,
    Leakage leakage,
    Pipeline pipeline,
    Cleaning cleaning,
    ModelChoice model,
    Threshold threshold,
    Evidence evidence
  ) {
    /** The six stages in their fixed order (stage n is {@code stages().get(n - 1)}); entries are null when missing. */
    public List<MlStageText> stages() {
      return java.util.Arrays.asList(leakage, pipeline, cleaning, model, threshold, evidence);
    }
  }

  /** The texts every ML stage carries; {@code prompt} is optional. */
  public sealed interface MlStageText permits Leakage, Pipeline, Cleaning, ModelChoice, Threshold, Evidence {
    String name();

    String title();

    String prompt();

    String note();

    String action();

    String passMessage();

    String wrongMessage();
  }

  /** Stage 1: the team keeps the features that exist at prediction time. */
  public record Leakage(
    String name,
    String title,
    String prompt,
    String note,
    String action,
    String passMessage,
    String wrongMessage,
    List<String> features,
    List<String> kept
  ) implements MlStageText {}

  /** Stage 2: the team orders the steps; {@code relabel} renames an item once it is after another one. */
  public record Pipeline(
    String name,
    String title,
    String prompt,
    String note,
    String action,
    String passMessage,
    String wrongMessage,
    List<Item> items,
    Relabel relabel,
    List<String> startOrder,
    List<String> order
  ) implements MlStageText {}

  public record Item(String id, String label) {}

  public record Relabel(String item, String after, String label) {}

  /** Stage 3: one option for missing values and one for outliers. */
  public record Cleaning(
    String name,
    String title,
    String prompt,
    String note,
    String action,
    String passMessage,
    String wrongMessage,
    Table table,
    Choice missing,
    Choice outliers,
    CleaningAnswer answer
  ) implements MlStageText {}

  public record Table(List<String> columns, List<List<String>> rows) {}

  public record Choice(String label, List<Option> options) {}

  public record Option(String id, String label) {}

  public record CleaningAnswer(String missing, String outliers) {}

  /** Stage 4: one model that meets both requirements. */
  public record ModelChoice(
    String name,
    String title,
    String prompt,
    String note,
    String action,
    String passMessage,
    String wrongMessage,
    List<Model> models,
    ModelRequirement requirement,
    String answer
  ) implements MlStageText {}

  public record Model(String id, String name, BigDecimal precision, BigDecimal recall) {}

  public record ModelRequirement(BigDecimal minRecall, BigDecimal minPrecision) {}

  /** Stage 5: a threshold on the slider whose metrics ({@link ThresholdMetrics}) meet the requirement. */
  public record Threshold(
    String name,
    String title,
    String prompt,
    String note,
    String action,
    String passMessage,
    String wrongMessage,
    String label,
    Slider slider,
    ThresholdRequirement requirement
  ) implements MlStageText {}

  public record Slider(BigDecimal min, BigDecimal max, BigDecimal step, BigDecimal start) {}

  public record ThresholdRequirement(BigDecimal minRecall, BigDecimal minPrecision, Integer maxFalseNegatives) {}

  /** Stage 6: the word read from the records. */
  public record Evidence(
    String name,
    String title,
    String prompt,
    String note,
    String action,
    String passMessage,
    String wrongMessage,
    List<EvidenceRecord> records,
    String inputLabel,
    String placeholder,
    String answer
  ) implements MlStageText {}

  public record EvidenceRecord(String id, String time, String device, String confidence) {}

  // ---- ARDUINO: one program to debug -----------------------------------------------------------------------------

  /**
   * The program is {@code lines} (numbered 1..N in order); {@code slot} is the choice placed after line
   * {@code slot.afterLine}. A bug is a set of lines with what fixes it; a test passes when all its bugs are fixed.
   */
  public record Arduino(
    String title,
    String intro,
    String badge,
    String brief,
    String warning,
    List<String> expectedBehaviour,
    String fileName,
    String action,
    String tokenLabel,
    String token,
    String passMessage,
    String wrongMessage,
    String outsideChangeMessage,
    List<CodeLine> lines,
    Slot slot,
    List<Bug> bugs,
    List<Test> tests
  ) {}

  public record CodeLine(Integer n, String code) {}

  /** The missing-behaviour choice: its id is a bug id the tests may name; {@code correct} is the right option's id. */
  public record Slot(String id, Integer afterLine, String prompt, List<SlotOption> options, String correct) {}

  public record SlotOption(String id, String code) {}

  /**
   * REPLACE: the one line must equal one of {@code accepted} once all whitespace is removed. REMOVE: every line must be
   * empty or start with "//".
   */
  public record Bug(String id, String kind, List<Integer> lines, List<String> accepted) {
    public static final String REPLACE = "REPLACE";
    public static final String REMOVE = "REMOVE";

    public boolean removes() {
      return REMOVE.equals(kind);
    }
  }

  public record Test(String id, String name, List<String> bugs) {}
}
