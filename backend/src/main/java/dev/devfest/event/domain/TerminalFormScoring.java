package dev.devfest.event.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The 15 points of a Linux terminal room's form (rules §18.8): 3 for each correct PATH, USERNAME, FILE_SIZE and
 * PATTERN and 3 when BOTH the encrypted message and the final clue are correct. Computed on every read from the
 * stored answers, the current keys and the organizer's overrides; no total is ever stored. An override awards or
 * denies one item's points whatever the keys say. The points stay inside this room: the event's ranking does not use
 * them.
 */
public final class TerminalFormScoring {

  public static final int POINTS_PER_ITEM = 3;
  public static final int MAX_POINTS = POINTS_PER_ITEM * TerminalScoreItem.values().length;

  /** {@code override}: null = automatic; {@code awarded}: the effective outcome; {@code points}: 3 or 0. */
  public record Item(TerminalScoreItem item, boolean auto, Boolean override, boolean awarded, int points) {}

  public record Score(List<Item> items, int total, boolean messageCorrect, boolean clueCorrect) {}

  private TerminalFormScoring() {}

  /** @param clueKey the room's clue key (the final clue uses the usual clue check) */
  public static Score score(TerminalAnswerKeys keys, TerminalFormValues values, String clueKey, Map<TerminalScoreItem, Boolean> overrides) {
    boolean message = keys.messageCorrect(values.encryptedMessage());
    boolean clue = ClueCheck.isCorrect(values.finalClue(), clueKey);
    Map<TerminalScoreItem, Boolean> auto = new EnumMap<>(TerminalScoreItem.class);
    auto.put(TerminalScoreItem.PATH, keys.pathCorrect(values.path()));
    auto.put(TerminalScoreItem.USERNAME, keys.usernameCorrect(values.username()));
    auto.put(TerminalScoreItem.FILE_SIZE, keys.fileSizeCorrect(values.fileSize()));
    auto.put(TerminalScoreItem.PATTERN, keys.patternCorrect(values.pattern()));
    auto.put(TerminalScoreItem.MESSAGE_CLUE, message && clue);
    return total(auto, overrides, message, clue);
  }

  /** The score of a team that has not started: nothing is correct, but an override still counts. */
  public static Score notStarted(Map<TerminalScoreItem, Boolean> overrides) {
    Map<TerminalScoreItem, Boolean> auto = new EnumMap<>(TerminalScoreItem.class);
    for (TerminalScoreItem item : TerminalScoreItem.values()) auto.put(item, false);
    return total(auto, overrides, false, false);
  }

  private static Score total(Map<TerminalScoreItem, Boolean> auto, Map<TerminalScoreItem, Boolean> overrides, boolean message, boolean clue) {
    List<Item> items = new ArrayList<>();
    int total = 0;
    for (TerminalScoreItem item : TerminalScoreItem.values()) {
      Boolean override = overrides.get(item);
      boolean awarded = override != null ? override : auto.get(item);
      int points = awarded ? POINTS_PER_ITEM : 0;
      items.add(new Item(item, auto.get(item), override, awarded, points));
      total += points;
    }
    return new Score(List.copyOf(items), total, message, clue);
  }
}
