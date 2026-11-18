package dev.devfest.event.domain.sentinel;

import dev.devfest.event.domain.sentinel.SentinelContent.ChoiceOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The order in which a team sees the options of a CHOICE stage (rules §19.2): shuffled, but decided only by the team
 * and the stage number, so the same team sees the same order on every refresh and on every device, and two teams
 * beside each other do not see the same one. Pure. Answers are option ids, never positions.
 */
public final class ChoiceOrder {

  private ChoiceOrder() {}

  /** A new list with the options in this team's order for this stage; the input is not changed. */
  public static List<ChoiceOption> shuffled(List<ChoiceOption> options, long teamId, int stageNumber) {
    List<ChoiceOption> order = new ArrayList<>(options);
    Collections.shuffle(order, new Random(seed(teamId, stageNumber)));
    return order;
  }

  /** SplitMix64 over (team, stage): neighbouring team ids and stage numbers give unrelated seeds. */
  static long seed(long teamId, int stageNumber) {
    long z = teamId * 0x9E3779B97F4A7C15L + stageNumber * 0xD1B54A32D192ED03L + 0x632BE59BD9B4E019L;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }
}
