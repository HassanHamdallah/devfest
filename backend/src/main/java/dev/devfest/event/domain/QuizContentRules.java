package dev.devfest.event.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a valid AI room question or repair looks like (rules §19.2), and the editor's planning warnings. The
 * warnings never block anything: the room's manager decides what the room asks.
 */
public final class QuizContentRules {

  public static final int MIN_OPTIONS = 2;
  public static final int MAX_OPTIONS = 6;
  public static final int MIN_SNIPPETS = 4;
  public static final int MAX_SNIPPETS = 6;
  public static final int MAX_QUESTION_POINTS = 10;
  public static final int MAX_REPAIR_POINTS = 100;
  public static final int DEFAULT_REPAIR_POINTS = 10;
  public static final int EXPECTED_REPAIRS = 3;
  public static final int EXPECTED_TOTAL_POINTS = 100;
  public static final int MAX_QUESTIONS = 100;
  public static final int MAX_REPAIRS = 10;
  public static final int DEFAULT_MCQ_MINUTES = 14;
  public static final int DEFAULT_PASS_SCORE = 60;

  private QuizContentRules() {}

  /** Empty when the question is valid; else the message for the editor. {@code correctOption} is 0-based. */
  public static Optional<String> questionProblem(
    QuizSection section,
    String prompt,
    List<String> options,
    Integer correctOption,
    Integer points,
    String explanation
  ) {
    if (section == null) return Optional.of("Choose a section.");
    if (isBlank(prompt)) return Optional.of("Enter the question.");
    if (bytes(prompt) > 2000) return Optional.of("The question is too long (max 2000 bytes).");
    if (options == null || options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
      return Optional.of("A question needs " + MIN_OPTIONS + " to " + MAX_OPTIONS + " options.");
    }
    for (String option : options) {
      if (isBlank(option)) return Optional.of("Every option needs a text.");
      if (bytes(option) > 500) return Optional.of("An option is too long (max 500 bytes).");
    }
    if (options.stream().map(String::strip).distinct().count() != options.size()) {
      return Optional.of("Two options have the same text.");
    }
    if (correctOption == null || correctOption < 0 || correctOption >= options.size()) {
      return Optional.of("Mark exactly one option as correct.");
    }
    if (points == null || points < 1 || points > MAX_QUESTION_POINTS) {
      return Optional.of("Points must be between 1 and " + MAX_QUESTION_POINTS + ".");
    }
    if (explanation != null && bytes(explanation) > 2000) return Optional.of("The explanation is too long (max 2000 bytes).");
    return Optional.empty();
  }

  /** Empty when the repair is valid. {@code snippets} are in their correct order. */
  public static Optional<String> repairProblem(String title, String instructions, Integer points, List<String> snippets) {
    if (isBlank(title)) return Optional.of("Enter a title.");
    if (bytes(title) > 120) return Optional.of("The title is too long (max 120 bytes).");
    if (instructions != null && bytes(instructions) > 2000) return Optional.of("The instructions are too long (max 2000 bytes).");
    if (points == null || points < 1 || points > MAX_REPAIR_POINTS) {
      return Optional.of("Points must be between 1 and " + MAX_REPAIR_POINTS + ".");
    }
    if (snippets == null || snippets.size() < MIN_SNIPPETS || snippets.size() > MAX_SNIPPETS) {
      return Optional.of("A repair needs " + MIN_SNIPPETS + " to " + MAX_SNIPPETS + " snippets.");
    }
    for (String snippet : snippets) {
      if (isBlank(snippet)) return Optional.of("Every snippet needs code.");
      if (bytes(snippet) > 2000) return Optional.of("A snippet is too long (max 2000 bytes).");
    }
    if (snippets.stream().distinct().count() != snippets.size()) {
      return Optional.of("Two snippets are identical, so their order could not be told apart.");
    }
    return Optional.empty();
  }

  /** Planning warnings: question counts per section, a repair count other than planned, a total other than 100 points. */
  public static List<String> warnings(Map<QuizSection, Integer> questionsBySection, int repairs, int totalPoints) {
    List<String> warnings = new ArrayList<>();
    for (QuizSection section : QuizSection.values()) {
      int count = questionsBySection.getOrDefault(section, 0);
      if (count != section.expectedCount()) {
        warnings.add("%s has %d questions (planned %d).".formatted(section.label(), count, section.expectedCount()));
      }
    }
    if (repairs != EXPECTED_REPAIRS) {
      warnings.add("There are %d repairs (planned %d).".formatted(repairs, EXPECTED_REPAIRS));
    }
    if (totalPoints != EXPECTED_TOTAL_POINTS) {
      warnings.add("The total is %d points (planned %d).".formatted(totalPoints, EXPECTED_TOTAL_POINTS));
    }
    return warnings;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static int bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }
}
