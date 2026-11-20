package dev.devfest.event.domain.recovery;

import dev.devfest.event.domain.EventRuleException;
import dev.devfest.event.domain.recovery.RecoveryLevels.Arduino;
import dev.devfest.event.domain.recovery.RecoveryLevels.Bug;
import dev.devfest.event.domain.recovery.RecoveryLevels.SlotOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Arduino level's hidden tests. The team sends every program line (edited or not) and one option for the missing
 * behaviour. Every line that belongs to no bug must be unchanged once all whitespace is removed, otherwise every test
 * fails ("a line outside the faulty logic was changed"). A REPLACE bug is fixed when its line, without whitespace,
 * equals one accepted variant; a REMOVE bug when each of its lines is empty or starts with "//"; the slot when the
 * chosen option is the correct one. A test passes when all its bugs are fixed. Pure.
 */
public final class ArduinoCheck {

  /** The longest program line accepted. */
  public static final int MAX_LINE = 300;

  public record TestResult(String id, String name, boolean passed) {}

  /** {@code outsideChanged}: a line outside the bugs was edited, so every test failed. */
  public record Run(boolean outsideChanged, List<TestResult> tests) {
    public boolean passed() {
      return !outsideChanged && tests.stream().allMatch(TestResult::passed);
    }
  }

  private ArduinoCheck() {}

  /** @param optionId the content id of the chosen slot option (already mapped from the team's shuffled letter) */
  public static Run run(Arduino program, List<String> lines, String optionId) {
    requireShape(program, lines, optionId);
    Set<Integer> bugLines = new HashSet<>();
    for (Bug bug : program.bugs()) bugLines.addAll(bug.lines());
    boolean outsideChanged = java.util.stream.IntStream
      .rangeClosed(1, program.lines().size())
      .filter(n -> !bugLines.contains(n))
      .anyMatch(n -> !withoutWhitespace(lines.get(n - 1)).equals(withoutWhitespace(program.lines().get(n - 1).code())));
    Map<String, Boolean> fixed = new HashMap<>();
    for (Bug bug : program.bugs()) fixed.put(bug.id(), !outsideChanged && fixed(bug, lines));
    fixed.put(program.slot().id(), !outsideChanged && program.slot().correct().equals(optionId));
    List<TestResult> tests = program
      .tests()
      .stream()
      .map(test -> new TestResult(test.id(), test.name(), !outsideChanged && test.bugs().stream().allMatch(fixed::get)))
      .toList();
    return new Run(outsideChanged, tests);
  }

  static boolean fixed(Bug bug, List<String> lines) {
    if (bug.removes()) {
      return bug.lines().stream().map(n -> lines.get(n - 1).strip()).allMatch(line -> line.isEmpty() || line.startsWith("//"));
    }
    String line = withoutWhitespace(lines.get(bug.lines().getFirst() - 1));
    return bug.accepted().stream().map(ArduinoCheck::withoutWhitespace).anyMatch(line::equals);
  }

  /** The text with every whitespace character removed. */
  public static String withoutWhitespace(String text) {
    StringBuilder kept = new StringBuilder(text.length());
    text.codePoints().filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)).forEach(kept::appendCodePoint);
    return kept.toString();
  }

  private static void requireShape(Arduino program, List<String> lines, String optionId) {
    int expected = program.lines().size();
    if (lines == null || lines.size() != expected || lines.stream().anyMatch(Objects::isNull)) {
      throw new EventRuleException("Send every line of the program (" + expected + " lines).");
    }
    for (String line : lines) {
      if (line.length() > MAX_LINE || line.contains("\n") || line.contains("\r")) {
        throw new EventRuleException("Each line is one line of at most " + MAX_LINE + " characters.");
      }
    }
    if (optionId == null || program.slot().options().stream().map(SlotOption::id).noneMatch(optionId::equals)) {
      throw new EventRuleException("Choose one of the options for the missing behavior.");
    }
  }
}
