package dev.devfest.event.domain;

/** Key-based clue checking. A room whose key is empty or blank accepts no answer and is not ready to open. */
public final class ClueCheck {

  private ClueCheck() {}

  public static boolean isCorrect(String answer, String key) {
    return AnswerNormalizer.matches(answer, key);
  }

  public static boolean hasKey(String key) {
    return !AnswerNormalizer.normalize(key).isEmpty();
  }

  /** True when the two keys accept different answers, i.e. existing answers must be checked again. */
  public static boolean keyChanged(String oldKey, String newKey) {
    return !AnswerNormalizer.normalize(oldKey).equals(AnswerNormalizer.normalize(newKey));
  }
}
