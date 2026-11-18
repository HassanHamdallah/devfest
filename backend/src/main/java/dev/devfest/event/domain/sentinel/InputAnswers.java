package dev.devfest.event.domain.sentinel;

import dev.devfest.event.domain.sentinel.SentinelContent.Answer;
import dev.devfest.event.domain.sentinel.SentinelContent.NumberAnswer;
import dev.devfest.event.domain.sentinel.SentinelContent.TextAnswer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * How a typed answer of an INPUT stage is read and judged (rules §19.2). Pure.
 *
 * <ul>
 *   <li>TEXT: what was typed is trimmed, every space is removed and the letters are lower-cased, and the result must
 *       equal one of the accepted values in the same form ("101 001" equals "101001").
 *   <li>NUMBER: a plain decimal number only, with a point or a comma ("0.19", ".19", "0,19", "4", "-2.5", "+3");
 *       no exponent, no thousands separator, no text. It is correct when it is within the tolerance of the value.
 * </ul>
 */
public final class InputAnswers {

  /** The most a team can type. */
  public static final int MAX_LENGTH = 40;
  public static final String NUMBER_HINT = "Enter a number, for example 0.5.";
  public static final String EMPTY = "Enter your answer.";
  public static final String TOO_LONG = "The answer can have at most " + MAX_LENGTH + " characters.";

  /** [+-] then digits with an optional fraction, or only a fraction (".19"); the separator is a point or a comma. */
  private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:\\d+(?:[.,]\\d*)?|[.,]\\d+)");
  /** Absorbs binary floating point (0.18 is 0.01 away from 0.19 only up to the last bit). */
  private static final double EPSILON = 1e-9;
  private static final int MAX_ACCEPTED = 10;

  private InputAnswers() {}

  /** Trimmed, without any space (any Unicode white space) and in lower case. */
  public static String normalize(String text) {
    if (text == null) return "";
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) out.append(c);
    }
    return out.toString().toLowerCase(Locale.ROOT);
  }

  /** The decimal number that was typed, or empty when it is not a plain decimal number. */
  public static Optional<Double> parseNumber(String text) {
    if (text == null) return Optional.empty();
    String trimmed = text.strip();
    if (!DECIMAL.matcher(trimmed).matches()) return Optional.empty();
    double value = Double.parseDouble(trimmed.replace(',', '.'));
    return Double.isFinite(value) ? Optional.of(value) : Optional.empty();
  }

  /**
   * Why {@code typed} cannot be judged, or empty when it can. A refused answer is not the team's one answer: nothing is
   * stored and the stage stays open.
   */
  public static Optional<String> refusal(Answer answer, String typed) {
    if (typed == null || typed.isBlank()) return Optional.of(answer instanceof NumberAnswer ? NUMBER_HINT : EMPTY);
    if (typed.strip().length() > MAX_LENGTH) return Optional.of(TOO_LONG);
    if (answer instanceof NumberAnswer && parseNumber(typed).isEmpty()) return Optional.of(NUMBER_HINT);
    return Optional.empty();
  }

  /** Whether a judgeable answer is right. */
  public static boolean correct(Answer answer, String typed) {
    return switch (answer) {
      case TextAnswer text -> {
        String given = normalize(typed);
        yield !given.isEmpty() && text.accepted().stream().map(InputAnswers::normalize).anyMatch(given::equals);
      }
      case NumberAnswer number -> parseNumber(typed).filter(value -> Math.abs(value - number.value()) <= number.tolerance() + EPSILON).isPresent();
    };
  }

  /** What an author got wrong in an answer (empty for a usable one). */
  public static List<String> problems(Answer answer) {
    List<String> problems = new ArrayList<>();
    switch (answer) {
      case TextAnswer text -> {
        if (text.accepted().isEmpty() || text.accepted().size() > MAX_ACCEPTED) problems.add("answer.accepted needs 1 to " + MAX_ACCEPTED + " values.");
        for (String accepted : text.accepted()) {
          String form = normalize(accepted);
          if (form.isEmpty()) problems.add("An accepted value cannot be empty.");
          else if (form.length() > MAX_LENGTH) problems.add("The accepted value \"" + accepted + "\" is longer than the " + MAX_LENGTH + " characters a team can type.");
        }
      }
      case NumberAnswer number -> {
        if (!Double.isFinite(number.value())) problems.add("answer.value must be a finite number.");
        if (!Double.isFinite(number.tolerance()) || number.tolerance() < 0) problems.add("answer.tolerance must be 0 or more.");
      }
    }
    return problems;
  }
}
