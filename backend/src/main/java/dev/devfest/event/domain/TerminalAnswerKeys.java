package dev.devfest.event.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The organizer's answer keys of a Linux terminal room (rules §18.8): PATH, USERNAME, FILE_SIZE and FILE_NAME (stored as {@code pattern}) each
 * accept several values (one per line). An empty key can never be matched, so the item cannot be awarded
 * automatically. The final clue is not here: it uses the room's clue key.
 *
 * <p>Comparison: every value is trimmed. PATH also ignores one trailing slash; FILE_NAME also ignores one pair of
 * surrounding quotes; FILE_SIZE ignores case; USERNAME is case-sensitive.
 */
public record TerminalAnswerKeys(String path, String username, String fileSize, String pattern) {

  public static final int MAX_VALUES = 20;
  public static final int MAX_VALUE_BYTES = 500;

  public static TerminalAnswerKeys empty() {
    return new TerminalAnswerKeys("", "", "", "");
  }

  /** Why the keys cannot be stored (too many values, a value too long, a NUL character), or empty when they can. */
  public Optional<String> problem() {
    String[] labels = { "PATH", "USERNAME", "FILE_SIZE", "FILE_NAME" };
    String[] texts = { path, username, fileSize, pattern };
    for (int i = 0; i < texts.length; i++) {
      if (texts[i].indexOf('\0') >= 0) return Optional.of(labels[i] + " must be text.");
      List<String> values = values(texts[i]);
      if (values.size() > MAX_VALUES) return Optional.of(labels[i] + " accepts at most " + MAX_VALUES + " values.");
      for (String value : values) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
          return Optional.of(labels[i] + " has a value that is too long (max " + MAX_VALUE_BYTES + " bytes).");
        }
      }
    }
    return Optional.empty();
  }

  /** The keys that have no value: the items they belong to cannot be awarded automatically. */
  public List<String> emptyKeys() {
    List<String> empty = new ArrayList<>();
    if (values(path).isEmpty()) empty.add("PATH");
    if (values(username).isEmpty()) empty.add("USERNAME");
    if (values(fileSize).isEmpty()) empty.add("FILE_SIZE");
    if (values(pattern).isEmpty()) empty.add("FILE_NAME");
    return empty;
  }

  public boolean pathCorrect(String given) {
    return matches(given, path, TerminalAnswerKeys::pathForm, false);
  }

  public boolean usernameCorrect(String given) {
    return matches(given, username, String::strip, false);
  }

  public boolean fileSizeCorrect(String given) {
    return matches(given, fileSize, String::strip, true);
  }

  public boolean patternCorrect(String given) {
    return matches(given, pattern, TerminalAnswerKeys::patternForm, false);
  }

  /** The non-blank lines of a multi-value key, trimmed. */
  static List<String> values(String text) {
    List<String> values = new ArrayList<>();
    for (String line : text.split("\r?\n")) {
      if (!line.isBlank()) values.add(line.strip());
    }
    return values;
  }

  private static boolean matches(String given, String keys, UnaryOperator<String> form, boolean ignoreCase) {
    if (given == null || given.isBlank()) return false;
    String candidate = form.apply(given);
    for (String key : values(keys)) {
      String expected = form.apply(key);
      if (expected.isEmpty()) continue;
      if (ignoreCase ? expected.equalsIgnoreCase(candidate) : expected.equals(candidate)) return true;
    }
    return false;
  }

  /** Trimmed, without one trailing slash (the root stays). */
  private static String pathForm(String text) {
    String trimmed = text.strip();
    return trimmed.length() > 1 && trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }

  /** Trimmed, without one pair of matching surrounding quotes. */
  private static String patternForm(String text) {
    String trimmed = text.strip();
    if (trimmed.length() >= 2) {
      char first = trimmed.charAt(0);
      if ((first == '"' || first == '\'') && trimmed.charAt(trimmed.length() - 1) == first) {
        return trimmed.substring(1, trimmed.length() - 1);
      }
    }
    return trimmed;
  }
}
