package dev.devfest.event.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Group balance for an organizer-chosen team count N: the four groups share N teams, a group holds at most
 * ceil(N / 4) active teams, and sizes should not differ by more than one. Only the cap is enforced (when a
 * team is created, moved or reactivated); everything else is a warning on the admin screens.
 */
public final class GroupCapacity {

  public static final int MIN_PLANNED_TEAMS = 1;
  public static final int MAX_PLANNED_TEAMS = 64;

  public static final String TEAM_COUNT = "TEAM_COUNT";
  public static final String GROUP_OVER_CAPACITY = "GROUP_OVER_CAPACITY";
  public static final String GROUP_UNBALANCED = "GROUP_UNBALANCED";

  /** A balance problem; {@code code} is one of the constants above. */
  public record Warning(String code, String message) {}

  private GroupCapacity() {}

  public static int maxPerGroup(int plannedTeams) {
    requirePlanned(plannedTeams);
    int groups = GroupCode.values().length;
    return (plannedTeams + groups - 1) / groups;
  }

  /** {@code activeTeams} may omit empty groups; they count as 0. */
  public static List<Warning> balanceWarnings(int plannedTeams, Map<GroupCode, Integer> activeTeams) {
    int max = maxPerGroup(plannedTeams);
    int[] sizes = Arrays.stream(GroupCode.values()).mapToInt(group -> activeTeams.getOrDefault(group, 0)).toArray();
    int total = Arrays.stream(sizes).sum();
    List<Warning> warnings = new ArrayList<>();
    if (total != plannedTeams) {
      warnings.add(new Warning(
        TEAM_COUNT,
        "There are %d active teams; the planned count is %d.".formatted(total, plannedTeams)
      ));
    }
    for (GroupCode group : GroupCode.values()) {
      int size = sizes[group.index()];
      if (size > max) {
        warnings.add(new Warning(
          GROUP_OVER_CAPACITY,
          "Group %s has %d active teams (max %d).".formatted(group, size, max)
        ));
      }
    }
    int smallest = Arrays.stream(sizes).min().orElse(0);
    int largest = Arrays.stream(sizes).max().orElse(0);
    if (largest - smallest > 1) {
      String detail = Arrays.stream(GroupCode.values())
        .map(group -> group + " " + sizes[group.index()])
        .collect(Collectors.joining(", "));
      warnings.add(new Warning(GROUP_UNBALANCED, "Group sizes differ by more than 1 (" + detail + ")."));
    }
    return warnings;
  }

  private static void requirePlanned(int plannedTeams) {
    if (plannedTeams < MIN_PLANNED_TEAMS || plannedTeams > MAX_PLANNED_TEAMS) {
      throw new EventRuleException(
        "The planned team count must be between %d and %d.".formatted(MIN_PLANNED_TEAMS, MAX_PLANNED_TEAMS)
      );
    }
  }
}
