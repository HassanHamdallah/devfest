package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import java.util.List;

/** The admin's teams and accounts screen. {@code groups} always lists A..D. */
public record Organization(
  List<AccountView> accounts,
  List<TeamView> teams,
  List<RoomManagerView> rooms,
  List<GroupTeamCount> groups
) {
  public record GroupTeamCount(GroupCode groupCode, int activeTeams) {}
}
