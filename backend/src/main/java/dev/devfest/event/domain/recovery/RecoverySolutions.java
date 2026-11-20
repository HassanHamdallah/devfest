package dev.devfest.event.domain.recovery;

import dev.devfest.event.domain.recovery.RecoveryLevels.Arduino;
import dev.devfest.event.domain.recovery.RecoveryLevels.Bug;
import dev.devfest.event.domain.recovery.RecoveryLevels.CodeLine;
import dev.devfest.event.domain.recovery.RecoveryLevels.EvidenceRecord;
import dev.devfest.event.domain.recovery.RecoveryLevels.Item;
import dev.devfest.event.domain.recovery.RecoveryLevels.Ml;
import dev.devfest.event.domain.recovery.RecoveryLevels.Model;
import dev.devfest.event.domain.recovery.RecoveryLevels.Option;
import dev.devfest.event.domain.recovery.RecoveryLevels.SlotOption;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What the organizers see on "Recovery solutions" (admin only): every ML answer with the reason, and the Arduino level's
 * bug list with the fixed lines, the right option and why each wrong option fails. The answers come from the imported
 * content itself ({@link RecoveryLevels}), so they can never disagree with what the server checks; the short
 * explanations are written for the shipped program and ML case (keyed by stage number, bug id and option id) and are
 * simply left out for an id they do not know. Pure.
 */
public final class RecoverySolutions {

  public record MlSolution(int stage, String name, List<String> answer, String why) {}

  /** One line to change: its number, the program's own text and an example of a fixed line (or what to do with it). */
  public record LineFix(int line, String original, String fix) {}

  /** One bug: what is wrong, the lines to change and the tests that fail while it stays. */
  public record BugSolution(String id, String what, String kind, List<LineFix> lines, List<String> acceptedVariants, List<String> failingTests) {}

  public record OptionSolution(String id, String code, boolean correct, String why) {}

  /** The missing behaviour: placed after line {@code afterLine}; {@code options} in the content's own order and ids. */
  public record SlotSolution(String id, int afterLine, String prompt, String correctOption, List<OptionSolution> options) {}

  public record ArduinoSolution(String fileName, List<BugSolution> bugs, SlotSolution slot, List<String> tests) {}

  public record Solutions(List<MlSolution> ml, ArduinoSolution arduino) {}

  private static final Map<Integer, String> ML_WHY = Map.of(
    1,
    "investigation_result is only known after the investigation, so a model cannot have it when it predicts. Using it is data leakage and is what makes the accuracy look like 96%.",
    2,
    "A scaler fitted on the full dataset has already seen the test rows. Split first, fit the scaler on the training data only, then use it to transform both parts.",
    3,
    "Zero is not a believable sensor reading and dropping the row throws data away, so the training median fills the gap. A signal of -210 is not possible: it is clipped or flagged, not ignored or averaged in.",
    4,
    "The requirements are recall at least 0.88 and precision at least 0.75, both. A model that wins only one of them does not pass.",
    5,
    "Raising the threshold trades recall for precision. Only the range shown here keeps recall, precision and false negatives inside the requirement together.",
    6,
    "Sort the four records by time, then read the last letter of each device name in that order."
  );

  private static final Map<String, String> BUG_WHAT = Map.of(
    "B1", "The authorization never expires: after 5 seconds it is set to true again instead of false.",
    "B2", "The alarm test is inverted: it fires for an authorized opening and stays quiet for an unauthorized one.",
    "B3", "The alarm switches itself off as soon as the door closes, but it must stay on until Reset is pressed.",
    "B4", "Motion is counted even when the door is closed; it must count only while the door is open.",
    "B5", "Reset gives authorization instead of clearing it."
  );

  /** A readable fixed line for each bug line of the shipped program (an example; every accepted variant also passes). */
  private static final Map<String, List<String>> BUG_FIX = Map.of(
    "B1", List.of("authorized = false;"),
    "B2", List.of("if (doorOpen && !authorized) {"),
    "B4", List.of("if (motionDetected && doorOpen) {"),
    "B5", List.of("authorized = false;")
  );

  private static final Map<String, String> OPTION_WHY = Map.of(
    "A",
    "It ends the window as soon as the door is closed while authorized, and that is already true right after the button is pressed, before the door was ever opened. The 5-second window dies at once, so a valid entry can never happen.",
    "B",
    "Correct: it remembers (entered) that the door was opened during the window and only ends the authorization after the door has closed again.",
    "C",
    "It clears authorization on every pass through loop(), so the button's window never lasts a single pass.",
    "D",
    "It clears authorization the moment the door opens, so the next pass sees an open door that is no longer authorized and raises the alarm for a valid entry."
  );

  private RecoverySolutions() {}

  public static Solutions of(RecoveryLevels levels) {
    return new Solutions(ml(levels.ml()), arduino(levels.arduino()));
  }

  // ---- ML ---------------------------------------------------------------------------------------------------------

  private static List<MlSolution> ml(Ml ml) {
    List<MlSolution> solutions = new ArrayList<>();
    solutions.add(new MlSolution(1, ml.leakage().name(), leakage(ml), ML_WHY.get(1)));
    solutions.add(new MlSolution(2, ml.pipeline().name(), pipeline(ml), ML_WHY.get(2)));
    solutions.add(new MlSolution(3, ml.cleaning().name(), cleaning(ml), ML_WHY.get(3)));
    solutions.add(new MlSolution(4, ml.model().name(), model(ml), ML_WHY.get(4)));
    solutions.add(new MlSolution(5, ml.threshold().name(), threshold(ml), ML_WHY.get(5)));
    solutions.add(new MlSolution(6, ml.evidence().name(), evidence(ml), ML_WHY.get(6)));
    return solutions;
  }

  private static List<String> leakage(Ml ml) {
    List<String> dropped = ml.leakage().features().stream().filter(f -> !ml.leakage().kept().contains(f)).toList();
    return List.of("Keep: " + String.join(", ", ml.leakage().kept()), "Remove: " + String.join(", ", dropped));
  }

  private static List<String> pipeline(Ml ml) {
    Map<String, Item> byId = ml.pipeline().items().stream().collect(Collectors.toMap(Item::id, item -> item));
    List<String> order = ml.pipeline().order();
    List<String> steps = new ArrayList<>();
    for (int i = 0; i < order.size(); i++) {
      Item item = byId.get(order.get(i));
      var relabel = ml.pipeline().relabel();
      boolean renamed = relabel != null && item.id().equals(relabel.item()) && order.indexOf(item.id()) > order.indexOf(relabel.after());
      steps.add((i + 1) + ". " + (renamed ? relabel.label() : item.label()));
    }
    return steps;
  }

  private static List<String> cleaning(Ml ml) {
    return List.of(
      ml.cleaning().missing().label() + ": " + label(ml.cleaning().missing().options(), ml.cleaning().answer().missing()),
      ml.cleaning().outliers().label() + ": " + label(ml.cleaning().outliers().options(), ml.cleaning().answer().outliers())
    );
  }

  private static String label(List<Option> options, String id) {
    return options.stream().filter(option -> option.id().equals(id)).map(Option::label).findFirst().orElse(id);
  }

  private static List<String> model(Ml ml) {
    var requirement = ml.model().requirement();
    List<String> lines = new ArrayList<>();
    for (Model model : ml.model().models()) {
      List<String> misses = new ArrayList<>();
      if (model.recall().compareTo(requirement.minRecall()) < 0) misses.add("recall " + model.recall() + " is below " + requirement.minRecall());
      if (model.precision().compareTo(requirement.minPrecision()) < 0) misses.add("precision " + model.precision() + " is below " + requirement.minPrecision());
      boolean right = model.id().equals(ml.model().answer());
      lines.add((right ? "Choose " : "Not ") + model.name() + (misses.isEmpty() ? " (precision " + model.precision() + ", recall " + model.recall() + ")" : ": " + String.join(" and ", misses)));
    }
    lines.sort(Comparator.comparing((String line) -> !line.startsWith("Choose ")));
    return lines;
  }

  private static List<String> threshold(Ml ml) {
    List<BigDecimal> passing = ThresholdMetrics.sliderValues(ml.threshold().slider()).stream().filter(t -> ThresholdMetrics.passes(t, ml.threshold().requirement())).toList();
    if (passing.isEmpty()) return List.of("No threshold on the slider passes.");
    BigDecimal low = passing.getFirst();
    BigDecimal high = passing.getLast();
    return List.of(
      "Any threshold from " + low.setScale(2) + " to " + high.setScale(2) + " (" + passing.size() + " slider steps).",
      "At " + low.setScale(2) + ": " + metrics(ThresholdMetrics.at(low)),
      "At " + high.setScale(2) + ": " + metrics(ThresholdMetrics.at(high))
    );
  }

  private static String metrics(ThresholdMetrics.Metrics m) {
    return "precision " + m.precision() + ", recall " + m.recall() + ", F1 " + m.f1() + ", false negatives " + m.falseNegatives();
  }

  private static List<String> evidence(Ml ml) {
    List<EvidenceRecord> sorted = ml.evidence().records().stream().sorted(Comparator.comparing(EvidenceRecord::time)).toList();
    List<String> lines = new ArrayList<>();
    StringBuilder word = new StringBuilder();
    for (EvidenceRecord record : sorted) {
      String suffix = record.device().substring(record.device().lastIndexOf('-') + 1);
      word.append(suffix);
      lines.add(record.time() + "  " + record.id() + "  " + record.device() + "  → " + suffix);
    }
    lines.add("Word: " + ml.evidence().answer() + (word.toString().equalsIgnoreCase(ml.evidence().answer()) ? "" : " (the records read " + word + ")"));
    return lines;
  }

  // ---- ARDUINO ----------------------------------------------------------------------------------------------------

  private static ArduinoSolution arduino(Arduino a) {
    Map<String, String> originals = a.lines().stream().collect(Collectors.toMap(line -> String.valueOf(line.n()), CodeLine::code));
    List<BugSolution> bugs = new ArrayList<>();
    for (Bug bug : a.bugs()) {
      List<LineFix> lines = new ArrayList<>();
      List<String> fixes = BUG_FIX.get(bug.id());
      for (int i = 0; i < bug.lines().size(); i++) {
        int n = bug.lines().get(i);
        String fix = bug.removes()
          ? "Comment out or delete this line"
          : fixes != null && i < fixes.size() ? fixes.get(i) : bug.accepted().getFirst();
        lines.add(new LineFix(n, originals.get(String.valueOf(n)).strip(), fix));
      }
      List<String> failing = a.tests().stream().filter(test -> test.bugs().contains(bug.id())).map(test -> test.id() + " " + test.name()).toList();
      bugs.add(new BugSolution(bug.id(), BUG_WHAT.get(bug.id()), bug.kind(), lines, bug.removes() ? List.of() : bug.accepted(), failing));
    }
    var slot = a.slot();
    List<OptionSolution> options = new ArrayList<>();
    for (SlotOption option : slot.options()) {
      options.add(new OptionSolution(option.id(), option.code(), option.id().equals(slot.correct()), OPTION_WHY.get(option.id())));
    }
    List<String> slotTests = a.tests().stream().filter(test -> test.bugs().contains(slot.id())).map(test -> test.id() + " " + test.name()).toList();
    List<String> tests = a.tests().stream().map(test -> test.id() + " " + test.name() + (test.bugs().isEmpty() ? " (passes unchanged)" : " — needs " + String.join(", ", test.bugs()))).toList();
    SlotSolution slotSolution = new SlotSolution(slot.id(), slot.afterLine(), slot.prompt(), slot.correct(), options);
    if (!slotTests.isEmpty()) bugs.add(new BugSolution(slot.id(), "The missing behaviour: after a valid entry (door opened, then closed) the authorization must end.", "SLOT", List.of(), List.of(), slotTests));
    return new ArduinoSolution(a.fileName(), bugs, slotSolution, tests);
  }
}
