package dev.devfest.event.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Groups for newly generated teams: each new team joins the currently smallest group (ties A→D). */
public final class RoundRobinGroups {

  private RoundRobinGroups() {}

  /**
   * One group per new team, in creation order. Empty when a team would have to exceed {@code maxPerGroup},
   * so the caller creates nothing rather than part of the request. {@code startingCounts} may omit empty groups.
   */
  public static Optional<List<GroupCode>> assign(Map<GroupCode, Integer> startingCounts, int count, int maxPerGroup) {
    if (count < 0) throw new IllegalArgumentException("count must not be negative");
    Map<GroupCode, Integer> sizes = new EnumMap<>(GroupCode.class);
    for (GroupCode group : GroupCode.values()) sizes.put(group, startingCounts.getOrDefault(group, 0));
    List<GroupCode> assigned = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      GroupCode smallest = GroupCode.A;
      for (GroupCode group : GroupCode.values()) {
        if (sizes.get(group) < sizes.get(smallest)) smallest = group;
      }
      if (sizes.get(smallest) >= maxPerGroup) return Optional.empty();
      sizes.merge(smallest, 1, Integer::sum);
      assigned.add(smallest);
    }
    return Optional.of(List.copyOf(assigned));
  }
}
