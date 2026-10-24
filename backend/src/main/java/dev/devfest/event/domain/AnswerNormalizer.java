package dev.devfest.event.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** Answer comparison: whitespace and letter case are forgiven, punctuation is not. */
public final class AnswerNormalizer {

  private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

  private AnswerNormalizer() {}

  public static String normalize(String value) {
    if (value == null) return "";
    return WHITESPACE_RUN.matcher(value).replaceAll(" ").strip().toUpperCase(Locale.ROOT);
  }

  /** An empty key never matches, so an unconfigured room cannot be solved by an empty answer. */
  public static boolean matches(String answer, String key) {
    String normalizedKey = normalize(key);
    return !normalizedKey.isEmpty() && normalizedKey.equals(normalize(answer));
  }
}
