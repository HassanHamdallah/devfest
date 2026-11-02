package dev.devfest.event.repository;

import dev.devfest.event.domain.GroupCode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Number of active teams in a group. */
public record GroupSizeRecord(GroupCode groupCode, int activeTeams) {
  public static Map<GroupCode, Integer> asMap(List<GroupSizeRecord> groups) {
    Map<GroupCode, Integer> sizes = new EnumMap<>(GroupCode.class);
    groups.forEach(group -> sizes.put(group.groupCode(), group.activeTeams()));
    return sizes;
  }
}
