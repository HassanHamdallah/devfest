package dev.devfest.event.domain.recovery;

import dev.devfest.event.domain.recovery.RecoveryLevels.Arduino;
import dev.devfest.event.domain.recovery.RecoveryLevels.Bug;
import dev.devfest.event.domain.recovery.RecoveryLevels.Choice;
import dev.devfest.event.domain.recovery.RecoveryLevels.Cleaning;
import dev.devfest.event.domain.recovery.RecoveryLevels.CodeLine;
import dev.devfest.event.domain.recovery.RecoveryLevels.Evidence;
import dev.devfest.event.domain.recovery.RecoveryLevels.EvidenceRecord;
import dev.devfest.event.domain.recovery.RecoveryLevels.Item;
import dev.devfest.event.domain.recovery.RecoveryLevels.Leakage;
import dev.devfest.event.domain.recovery.RecoveryLevels.Ml;
import dev.devfest.event.domain.recovery.RecoveryLevels.MlStageText;
import dev.devfest.event.domain.recovery.RecoveryLevels.Model;
import dev.devfest.event.domain.recovery.RecoveryLevels.ModelChoice;
import dev.devfest.event.domain.recovery.RecoveryLevels.Option;
import dev.devfest.event.domain.recovery.RecoveryLevels.Pipeline;
import dev.devfest.event.domain.recovery.RecoveryLevels.Slider;
import dev.devfest.event.domain.recovery.RecoveryLevels.Slot;
import dev.devfest.event.domain.recovery.RecoveryLevels.SlotOption;
import dev.devfest.event.domain.recovery.RecoveryLevels.Test;
import dev.devfest.event.domain.recovery.RecoveryLevels.Threshold;
import dev.devfest.event.domain.recovery.RecoveryLevels.ThresholdRequirement;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Everything the "recoveryLevels" part of the final's content file must satisfy: every text present and within its
 * length, every id unique, every answer one of the listed choices, and every answer actually reachable (a threshold on
 * the slider passes, exactly one model meets the requirement, every bug and the slot are covered by a test). Every
 * problem is listed; none stops the others. Pure.
 */
public final class RecoveryContentRules {

  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
  private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
  private static final int MAX_TITLE = 120;
  private static final int MAX_TEXT = 2000;
  private static final int MAX_LABEL = 120;
  private static final int MAX_CELL = 60;
  private static final int MAX_TOKEN = 40;
  private static final int MAX_LINES = 400;
  private static final int MAX_OPTION_CODE = 600;
  private static final int MAX_SLIDER_STEPS = 1000;
  private static final String ML = "recoveryLevels.ml";
  private static final String ARDUINO = "recoveryLevels.arduino";

  private RecoveryContentRules() {}

  public static List<String> problems(RecoveryLevels levels) {
    List<String> problems = new ArrayList<>();
    if (levels == null) {
      problems.add("recoveryLevels: required, with \"ml\" and \"arduino\".");
      return problems;
    }
    if (levels.ml() == null) problems.add(ML + ": required.");
    else ml(levels.ml(), problems);
    if (levels.arduino() == null) problems.add(ARDUINO + ": required.");
    else arduino(levels.arduino(), problems);
    return problems;
  }

  // ---- ML ---------------------------------------------------------------------------------------------------------

  private static void ml(Ml ml, List<String> problems) {
    text(ML + ".title", ml.title(), MAX_TITLE, problems);
    text(ML + ".intro", ml.intro(), MAX_TEXT, problems);
    text(ML + ".badge", ml.badge(), MAX_LABEL, problems);
    text(ML + ".tokenLabel", ml.tokenLabel(), MAX_LABEL, problems);
    text(ML + ".token", ml.token(), MAX_TOKEN, problems);
    List<String> keys = List.of("leakage", "pipeline", "cleaning", "model", "threshold", "evidence");
    List<MlStageText> stages = ml.stages();
    for (int i = 0; i < stages.size(); i++) {
      String where = ML + "." + keys.get(i);
      MlStageText stage = stages.get(i);
      if (stage == null) {
        problems.add(where + ": required (stage " + (i + 1) + ").");
        continue;
      }
      stageTexts(where, stage, problems);
      switch (stage) {
        case Leakage s -> leakage(where, s, problems);
        case Pipeline s -> pipeline(where, s, problems);
        case Cleaning s -> cleaning(where, s, problems);
        case ModelChoice s -> model(where, s, problems);
        case Threshold s -> threshold(where, s, problems);
        case Evidence s -> evidence(where, s, problems);
      }
    }
  }

  private static void stageTexts(String where, MlStageText stage, List<String> problems) {
    text(where + ".name", stage.name(), MAX_LABEL, problems);
    text(where + ".title", stage.title(), MAX_TITLE, problems);
    if (stage.prompt() != null) text(where + ".prompt", stage.prompt(), MAX_TEXT, problems);
    text(where + ".note", stage.note(), MAX_TEXT, problems);
    text(where + ".action", stage.action(), MAX_LABEL, problems);
    text(where + ".passMessage", stage.passMessage(), MAX_TEXT, problems);
    text(where + ".wrongMessage", stage.wrongMessage(), MAX_TEXT, problems);
  }

  private static void leakage(String where, Leakage s, List<String> problems) {
    Set<String> features = uniqueIds(where + ".features", s.features(), 2, 20, problems);
    if (features == null) return;
    Set<String> kept = uniqueIds(where + ".kept", s.kept(), 1, 20, problems);
    if (kept != null && !features.containsAll(kept)) problems.add(where + ".kept: every kept feature must be one of the features.");
  }

  private static void pipeline(String where, Pipeline s, List<String> problems) {
    if (s.items() == null || s.items().size() < 2 || s.items().size() > 20 || hasNull(s.items())) {
      problems.add(where + ".items: from 2 to 20 steps are required.");
      return;
    }
    Set<String> ids = new HashSet<>();
    for (Item item : s.items()) {
      if (item.id() == null || !ID.matcher(item.id()).matches()) problems.add(where + ".items: every id must be 1 to 40 letters, digits, '_' or '-'.");
      else if (!ids.add(item.id())) problems.add(where + ".items: the id " + item.id() + " is used twice.");
      text(where + ".items." + item.id() + ".label", item.label(), MAX_LABEL, problems);
    }
    permutation(where + ".startOrder", s.startOrder(), ids, problems);
    permutation(where + ".order", s.order(), ids, problems);
    if (Objects.equals(s.startOrder(), s.order())) problems.add(where + ".order: must differ from startOrder.");
    if (s.relabel() != null) {
      if (!ids.contains(s.relabel().item()) || !ids.contains(s.relabel().after()) || Objects.equals(s.relabel().item(), s.relabel().after())) {
        problems.add(where + ".relabel: item and after must be two different step ids.");
      }
      text(where + ".relabel.label", s.relabel().label(), MAX_LABEL, problems);
    }
  }

  private static void cleaning(String where, Cleaning s, List<String> problems) {
    if (s.table() == null || s.table().columns() == null || s.table().rows() == null) {
      problems.add(where + ".table: columns and rows are required.");
    } else {
      int columns = s.table().columns().size();
      if (columns < 1 || columns > 10) problems.add(where + ".table.columns: from 1 to 10 columns are required.");
      cells(where + ".table.columns", s.table().columns(), problems);
      if (s.table().rows().isEmpty() || s.table().rows().size() > 50) problems.add(where + ".table.rows: from 1 to 50 rows are required.");
      for (int r = 0; r < s.table().rows().size(); r++) {
        List<String> row = s.table().rows().get(r);
        if (row == null || row.size() != columns) problems.add(where + ".table.rows[" + (r + 1) + "]: must have one cell per column.");
        else cells(where + ".table.rows[" + (r + 1) + "]", row, problems);
      }
    }
    Set<String> missing = choice(where + ".missing", s.missing(), problems);
    Set<String> outliers = choice(where + ".outliers", s.outliers(), problems);
    if (s.answer() == null || !missing.contains(s.answer().missing()) || !outliers.contains(s.answer().outliers())) {
      problems.add(where + ".answer: missing and outliers must each be one of their option ids.");
    }
  }

  private static void model(String where, ModelChoice s, List<String> problems) {
    boolean requirementOk = s.requirement() != null && fraction(s.requirement().minRecall()) && fraction(s.requirement().minPrecision());
    if (!requirementOk) problems.add(where + ".requirement: minRecall and minPrecision must be from 0 to 1.");
    if (s.models() == null || s.models().size() < 2 || s.models().size() > 10 || hasNull(s.models())) {
      problems.add(where + ".models: from 2 to 10 models are required.");
      return;
    }
    Set<String> ids = new HashSet<>();
    List<String> meeting = new ArrayList<>();
    for (Model model : s.models()) {
      if (model.id() == null || !ID.matcher(model.id()).matches()) problems.add(where + ".models: every id must be 1 to 40 letters, digits, '_' or '-'.");
      else if (!ids.add(model.id())) problems.add(where + ".models: the id " + model.id() + " is used twice.");
      text(where + ".models." + model.id() + ".name", model.name(), MAX_LABEL, problems);
      if (!fraction(model.precision()) || !fraction(model.recall())) {
        problems.add(where + ".models." + model.id() + ": precision and recall must be from 0 to 1.");
      } else if (requirementOk && model.recall().compareTo(s.requirement().minRecall()) >= 0 && model.precision().compareTo(s.requirement().minPrecision()) >= 0) {
        meeting.add(model.id());
      }
    }
    if (s.answer() == null || !ids.contains(s.answer())) problems.add(where + ".answer: must be the id of one of the models.");
    else if (requirementOk && !meeting.equals(List.of(s.answer()))) {
      problems.add(where + ".answer: must be the only model that meets the requirement (models meeting it: " + meeting + ").");
    }
  }

  private static void threshold(String where, Threshold s, List<String> problems) {
    text(where + ".label", s.label(), MAX_LABEL, problems);
    ThresholdRequirement req = s.requirement();
    boolean requirementOk = req != null && fraction(req.minRecall()) && fraction(req.minPrecision()) && req.maxFalseNegatives() != null && req.maxFalseNegatives() >= 0 && req.maxFalseNegatives() <= 20;
    if (!requirementOk) problems.add(where + ".requirement: minRecall and minPrecision from 0 to 1, maxFalseNegatives from 0 to 20.");
    Slider slider = s.slider();
    if (!sliderOk(slider)) {
      problems.add(where + ".slider: min < max, a step above 0 that divides max - min (at most " + MAX_SLIDER_STEPS + " steps), start on a step, all with at most 2 decimals, within 0 to 1.");
      return;
    }
    if (requirementOk && ThresholdMetrics.sliderValues(slider).stream().noneMatch(t -> ThresholdMetrics.passes(t, req))) {
      problems.add(where + ": no threshold on the slider meets the requirement.");
    }
  }

  private static void evidence(String where, Evidence s, List<String> problems) {
    if (s.records() == null || s.records().isEmpty() || s.records().size() > 20 || hasNull(s.records())) {
      problems.add(where + ".records: from 1 to 20 records are required.");
    } else {
      for (int i = 0; i < s.records().size(); i++) {
        EvidenceRecord record = s.records().get(i);
        cells(where + ".records[" + (i + 1) + "]", java.util.Arrays.asList(record.id(), record.time(), record.device(), record.confidence()), problems);
      }
    }
    text(where + ".inputLabel", s.inputLabel(), MAX_LABEL, problems);
    if (s.placeholder() != null) text(where + ".placeholder", s.placeholder(), MAX_LABEL, problems);
    text(where + ".answer", s.answer(), MlChecks.MAX_WORD, problems);
  }

  // ---- ARDUINO ----------------------------------------------------------------------------------------------------

  private static void arduino(Arduino a, List<String> problems) {
    text(ARDUINO + ".title", a.title(), MAX_TITLE, problems);
    text(ARDUINO + ".intro", a.intro(), MAX_TEXT, problems);
    text(ARDUINO + ".badge", a.badge(), MAX_LABEL, problems);
    text(ARDUINO + ".brief", a.brief(), MAX_TEXT, problems);
    text(ARDUINO + ".warning", a.warning(), MAX_TEXT, problems);
    text(ARDUINO + ".action", a.action(), MAX_LABEL, problems);
    text(ARDUINO + ".tokenLabel", a.tokenLabel(), MAX_LABEL, problems);
    text(ARDUINO + ".token", a.token(), MAX_TOKEN, problems);
    text(ARDUINO + ".passMessage", a.passMessage(), MAX_TEXT, problems);
    text(ARDUINO + ".wrongMessage", a.wrongMessage(), MAX_TEXT, problems);
    text(ARDUINO + ".outsideChangeMessage", a.outsideChangeMessage(), MAX_TEXT, problems);
    if (a.fileName() == null || !FILE_NAME.matcher(a.fileName()).matches()) problems.add(ARDUINO + ".fileName: must be a plain file name.");
    if (a.expectedBehaviour() == null || a.expectedBehaviour().isEmpty() || a.expectedBehaviour().size() > 20) {
      problems.add(ARDUINO + ".expectedBehaviour: from 1 to 20 lines are required.");
    } else {
      for (String line : a.expectedBehaviour()) text(ARDUINO + ".expectedBehaviour", line, 300, problems);
    }
    int lineCount = lines(a.lines(), problems);
    Set<String> fixable = new HashSet<>();
    slot(a.slot(), lineCount, fixable, problems);
    bugs(a, lineCount, fixable, problems);
    tests(a.tests(), fixable, problems);
  }

  /** Returns the number of program lines (0 when they are unusable). */
  private static int lines(List<CodeLine> lines, List<String> problems) {
    if (lines == null || lines.isEmpty() || lines.size() > MAX_LINES || hasNull(lines)) {
      problems.add(ARDUINO + ".lines: from 1 to " + MAX_LINES + " lines are required.");
      return 0;
    }
    boolean usable = true;
    for (int i = 0; i < lines.size(); i++) {
      CodeLine line = lines.get(i);
      if (line.n() == null || line.n() != i + 1) {
        problems.add(ARDUINO + ".lines[" + (i + 1) + "]: n must be " + (i + 1) + " (lines are numbered 1, 2, 3, ... in order).");
        usable = false;
      }
      if (line.code() == null || line.code().length() > ArduinoCheck.MAX_LINE || line.code().contains("\n") || line.code().contains("\r")) {
        problems.add(ARDUINO + ".lines[" + (i + 1) + "]: code is required: one line of at most " + ArduinoCheck.MAX_LINE + " characters (it may be empty).");
        usable = false;
      }
    }
    return usable ? lines.size() : 0;
  }

  private static void slot(Slot slot, int lineCount, Set<String> fixable, List<String> problems) {
    String where = ARDUINO + ".slot";
    if (slot == null) {
      problems.add(where + ": required.");
      return;
    }
    if (slot.id() == null || !ID.matcher(slot.id()).matches()) problems.add(where + ".id: 1 to 40 letters, digits, '_' or '-'.");
    else fixable.add(slot.id());
    if (lineCount > 0 && (slot.afterLine() == null || slot.afterLine() < 0 || slot.afterLine() > lineCount)) {
      problems.add(where + ".afterLine: must be from 0 to " + lineCount + ".");
    }
    text(where + ".prompt", slot.prompt(), 300, problems);
    if (slot.options() == null || slot.options().size() < 2 || slot.options().size() > 8 || hasNull(slot.options())) {
      problems.add(where + ".options: from 2 to 8 options are required.");
      return;
    }
    Set<String> ids = new HashSet<>();
    for (SlotOption option : slot.options()) {
      if (option.id() == null || !ID.matcher(option.id()).matches()) problems.add(where + ".options: every id must be 1 to 40 letters, digits, '_' or '-'.");
      else if (!ids.add(option.id())) problems.add(where + ".options: the id " + option.id() + " is used twice.");
      text(where + ".options." + option.id() + ".code", option.code(), MAX_OPTION_CODE, problems);
    }
    if (slot.correct() == null || !ids.contains(slot.correct())) problems.add(where + ".correct: must be the id of one of the options.");
  }

  private static void bugs(Arduino a, int lineCount, Set<String> fixable, List<String> problems) {
    String where = ARDUINO + ".bugs";
    if (a.bugs() == null || a.bugs().isEmpty() || a.bugs().size() > 20 || hasNull(a.bugs())) {
      problems.add(where + ": from 1 to 20 bugs are required.");
      return;
    }
    Set<Integer> used = new HashSet<>();
    for (Bug bug : a.bugs()) {
      String id = bug.id() == null ? "?" : bug.id();
      String at = where + "." + id;
      if (bug.id() == null || !ID.matcher(bug.id()).matches()) problems.add(where + ": every id must be 1 to 40 letters, digits, '_' or '-'.");
      else if (!fixable.add(bug.id())) problems.add(where + ": the id " + bug.id() + " is used twice (bugs and the slot share ids).");
      if (!Bug.REPLACE.equals(bug.kind()) && !Bug.REMOVE.equals(bug.kind())) problems.add(at + ".kind: must be REPLACE or REMOVE.");
      if (bug.lines() == null || bug.lines().isEmpty() || hasNull(bug.lines())) {
        problems.add(at + ".lines: at least one line number is required.");
        continue;
      }
      boolean linesOk = true;
      for (int n : bug.lines()) {
        if (lineCount > 0 && (n < 1 || n > lineCount)) {
          problems.add(at + ".lines: " + n + " is not a line of the program.");
          linesOk = false;
        } else if (!used.add(n)) {
          problems.add(at + ".lines: line " + n + " belongs to another bug.");
        }
      }
      if (Bug.REPLACE.equals(bug.kind())) {
        if (bug.lines().size() != 1) problems.add(at + ": a REPLACE bug has exactly one line.");
        if (bug.accepted() == null || bug.accepted().isEmpty() || bug.accepted().size() > 20) {
          problems.add(at + ".accepted: from 1 to 20 accepted lines are required.");
        } else {
          for (String accepted : bug.accepted()) text(at + ".accepted", accepted, ArduinoCheck.MAX_LINE, problems);
          if (linesOk && lineCount > 0 && bug.lines().size() == 1 && bug.accepted().stream().allMatch(Objects::nonNull)) {
            String original = a.lines().get(bug.lines().getFirst() - 1).code();
            if (ArduinoCheck.fixed(bug, a.lines().stream().map(CodeLine::code).toList())) {
              problems.add(at + ".accepted: the program's own line " + bug.lines().getFirst() + " (" + original.strip() + ") is accepted, so the bug is already fixed.");
            }
          }
        }
      } else if (Bug.REMOVE.equals(bug.kind())) {
        if (bug.accepted() != null && !bug.accepted().isEmpty()) problems.add(at + ".accepted: a REMOVE bug has no accepted lines.");
        if (linesOk && lineCount > 0 && ArduinoCheck.fixed(bug, a.lines().stream().map(CodeLine::code).toList())) {
          problems.add(at + ".lines: these lines are already empty or comments, so the bug is already fixed.");
        }
      }
    }
  }

  private static void tests(List<Test> tests, Set<String> fixable, List<String> problems) {
    String where = ARDUINO + ".tests";
    if (tests == null || tests.isEmpty() || tests.size() > 30 || hasNull(tests)) {
      problems.add(where + ": from 1 to 30 tests are required.");
      return;
    }
    Set<String> ids = new HashSet<>();
    Set<String> covered = new HashSet<>();
    for (Test test : tests) {
      if (test.id() == null || !ID.matcher(test.id()).matches()) problems.add(where + ": every id must be 1 to 40 letters, digits, '_' or '-'.");
      else if (!ids.add(test.id())) problems.add(where + ": the id " + test.id() + " is used twice.");
      text(where + "." + test.id() + ".name", test.name(), MAX_LABEL, problems);
      if (test.bugs() == null) {
        problems.add(where + "." + test.id() + ".bugs: required (it may be empty).");
        continue;
      }
      for (String bug : test.bugs()) {
        if (!fixable.contains(bug)) problems.add(where + "." + test.id() + ".bugs: " + bug + " is not a bug or the slot.");
        else covered.add(bug);
      }
    }
    for (String bug : fixable) {
      if (!covered.contains(bug)) problems.add(where + ": no test checks " + bug + ".");
    }
  }

  // ---- shared -----------------------------------------------------------------------------------------------------

  private static Set<String> uniqueIds(String where, List<String> ids, int min, int max, List<String> problems) {
    if (ids == null || ids.size() < min || ids.size() > max || hasNull(ids)) {
      problems.add(where + ": from " + min + " to " + max + " ids are required.");
      return null;
    }
    Set<String> unique = new HashSet<>();
    for (String id : ids) {
      if (!ID.matcher(id).matches()) problems.add(where + ": " + id + " must be 1 to 40 letters, digits, '_' or '-'.");
      else if (!unique.add(id)) problems.add(where + ": " + id + " is listed twice.");
    }
    return unique;
  }

  private static void permutation(String where, List<String> order, Set<String> ids, List<String> problems) {
    if (order == null || order.size() != ids.size() || !ids.equals(new HashSet<>(order))) {
      problems.add(where + ": must list every step id exactly once.");
    }
  }

  private static Set<String> choice(String where, Choice choice, List<String> problems) {
    Set<String> ids = new HashSet<>();
    if (choice == null || choice.options() == null || choice.options().size() < 2 || choice.options().size() > 10 || hasNull(choice.options())) {
      problems.add(where + ": a label and from 2 to 10 options are required.");
      return ids;
    }
    text(where + ".label", choice.label(), MAX_LABEL, problems);
    for (Option option : choice.options()) {
      if (option.id() == null || !ID.matcher(option.id()).matches()) problems.add(where + ".options: every id must be 1 to 40 letters, digits, '_' or '-'.");
      else if (!ids.add(option.id())) problems.add(where + ".options: the id " + option.id() + " is used twice.");
      text(where + ".options." + option.id() + ".label", option.label(), MAX_LABEL, problems);
    }
    return ids;
  }

  private static void cells(String where, List<String> cells, List<String> problems) {
    for (String cell : cells) {
      if (cell == null || cell.isBlank() || cell.length() > MAX_CELL) {
        problems.add(where + ": every value is required (at most " + MAX_CELL + " characters).");
        return;
      }
    }
  }

  private static boolean fraction(BigDecimal value) {
    return value != null && value.signum() >= 0 && value.compareTo(BigDecimal.ONE) <= 0;
  }

  private static boolean twoDecimals(BigDecimal value) {
    return value.stripTrailingZeros().scale() <= 2;
  }

  private static boolean sliderOk(Slider s) {
    if (s == null || s.min() == null || s.max() == null || s.step() == null || s.start() == null) return false;
    if (!fraction(s.min()) || !fraction(s.max()) || !fraction(s.start()) || s.min().compareTo(s.max()) >= 0 || s.step().signum() <= 0) return false;
    if (!twoDecimals(s.min()) || !twoDecimals(s.max()) || !twoDecimals(s.step()) || !twoDecimals(s.start())) return false;
    BigDecimal[] span = s.max().subtract(s.min()).divideAndRemainder(s.step());
    BigDecimal[] fromMin = s.start().subtract(s.min()).divideAndRemainder(s.step());
    return span[1].signum() == 0 && span[0].intValue() <= MAX_SLIDER_STEPS && s.start().compareTo(s.max()) <= 0 && fromMin[0].signum() >= 0 && fromMin[1].signum() == 0;
  }

  private static boolean hasNull(List<?> list) {
    return list.stream().anyMatch(Objects::isNull);
  }

  private static void text(String field, String value, int max, List<String> problems) {
    if (value == null || value.isBlank() || value.length() > max) {
      problems.add(field + " is required (at most " + max + " characters).");
    }
  }
}
