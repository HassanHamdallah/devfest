package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import java.util.List;

/**
 * The admin's teams and accounts screen. {@code groups} always lists A..D; {@code warnings} are the group
 * balance problems for {@code plannedTeamCount}; {@code groupsLocked} once round 1 has started.
 */
public record Organization(
  List<AccountView> accounts,
  List<TeamView> teams,
  List<RoomManagerView> rooms,
  List<GroupTeamCount> groups,
  int plannedTeamCount,
  int maxPerGroup,
  boolean groupsLocked,
  List<AdminAlert> warnings
) {
  public record GroupTeamCount(GroupCode groupCode, int activeTeams) {}
}
