package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.List;

/** One room on the admin board; {@code teamsNow} are the active teams of {@code groupNow}. */
public record AdminRoomCard(
  long id,
  RoomCode code,
  String name,
  int position,
  boolean open,
  Long managerId,
  String managerName,
  GroupCode groupNow,
  List<String> teamsNow,
  int passed,
  int failed,
  int pending,
  int totalTeams
) {}
