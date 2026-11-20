package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.recovery.ArduinoCheck.TestResult;
import dev.devfest.event.domain.recovery.RecoveryLevel;
import dev.devfest.event.domain.recovery.RecoveryLevels.Choice;
import dev.devfest.event.domain.recovery.RecoveryLevels.CodeLine;
import dev.devfest.event.domain.recovery.RecoveryLevels.EvidenceRecord;
import dev.devfest.event.domain.recovery.RecoveryLevels.Item;
import dev.devfest.event.domain.recovery.RecoveryLevels.Model;
import dev.devfest.event.domain.recovery.RecoveryLevels.Relabel;
import dev.devfest.event.domain.recovery.RecoveryLevels.Slider;
import dev.devfest.event.domain.recovery.RecoveryLevels.Table;
import dev.devfest.event.domain.recovery.ThresholdMetrics.Metrics;
import java.util.List;

/**
 * A team's recovery (GET /team/final/recovery), always built from the server's state. {@code levels}: the levels it must
 * pass, in order (empty with 4 keys). {@code currentLevel}/{@code currentStage}: the one stage it can check now (null
 * once finished or when none is needed); only that stage's public data is sent ({@code mlStage} or {@code arduino}).
 * {@code progress}: every required stage's passed flag, and a level's cosmetic token once it is passed.
 * {@code lastTests}: the latest Arduino run's PASS/FAIL per test. {@code waitSeconds}: before the next check is
 * accepted. It never carries an answer, an accepted line, the correct option or the passing threshold range.
 */
public record TeamRecovery(
  FinalStage status,
  List<RecoveryLevel> levels,
  boolean finished,
  RecoveryLevel currentLevel,
  Integer currentStage,
  List<LevelProgress> progress,
  MlStageView mlStage,
  ArduinoView arduino,
  List<TestResult> lastTests,
  int waitSeconds
) {
  /** One required level: its title, its stages' passed flags, and its token once passed. */
  public record LevelProgress(RecoveryLevel level, String title, List<StageFlag> stages, boolean passed, String tokenLabel, String token) {}

  public record StageFlag(int stage, String name, boolean passed) {}

  /** The current ML stage: its texts and exactly one of the six stage-specific parts. */
  public record MlStageView(
    int stage,
    String name,
    String title,
    String prompt,
    String note,
    String action,
    LeakageData leakage,
    PipelineData pipeline,
    CleaningData cleaning,
    ModelData model,
    ThresholdData threshold,
    EvidenceData evidence
  ) {}

  public record LeakageData(List<String> features) {}

  public record PipelineData(List<Item> items, List<String> startOrder, Relabel relabel) {}

  public record CleaningData(Table table, Choice missing, Choice outliers) {}

  public record ModelData(List<Model> models) {}

  /** The slider and the metrics at each of its values (computed on the server; the requirement is in the prompt). */
  public record ThresholdData(String label, Slider slider, List<Metrics> metrics) {}

  public record EvidenceData(List<EvidenceRecord> records, String inputLabel, String placeholder) {}

  /** The Arduino level: the program, the expected behaviour, the slot (options in this team's order) and the test names. */
  public record ArduinoView(
    String title,
    String intro,
    String badge,
    String brief,
    String warning,
    List<String> expectedBehaviour,
    String fileName,
    String action,
    List<CodeLine> lines,
    SlotView slot,
    List<TestName> tests
  ) {}

  /** The missing-behaviour choice placed after line {@code afterLine}; {@code options} are lettered in this team's order. */
  public record SlotView(int afterLine, String prompt, List<SlotChoice> options) {}

  public record SlotChoice(String letter, String code) {}

  public record TestName(String id, String name) {}
}
