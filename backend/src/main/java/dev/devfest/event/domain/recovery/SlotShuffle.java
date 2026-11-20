package dev.devfest.event.domain.recovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * The order in which one team sees the missing-behaviour options: a shuffle seeded by the team id, so it is the same on
 * every read and differs between teams. The team sees and sends letters (A, B, C, ... by position), never the content's
 * option ids, so a letter means a different option for another team. Pure.
 */
public final class SlotShuffle {

  private static final long SEED_MIX = 0x9E3779B97F4A7C15L;

  private SlotShuffle() {}

  /** The content's option ids in this team's order. */
  public static List<String> order(List<String> optionIds, long teamId) {
    List<String> shuffled = new ArrayList<>(optionIds);
    Collections.shuffle(shuffled, new Random(teamId * SEED_MIX + 1));
    return List.copyOf(shuffled);
  }

  /** The letter shown at position {@code index} (0 = "A"). */
  public static String letter(int index) {
    return String.valueOf((char) ('A' + index));
  }

  /** The content option id behind the letter this team chose; empty for an unknown letter. */
  public static Optional<String> optionFor(List<String> optionIds, long teamId, String letter) {
    List<String> order = order(optionIds, teamId);
    for (int i = 0; i < order.size(); i++) {
      if (letter(i).equals(letter)) return Optional.of(order.get(i));
    }
    return Optional.empty();
  }
}
