package dev.devfest.event.dto;

import java.util.List;

/** The admin control screen. {@code startWarnings}: closed rooms and rooms without a clue key (not blocking). */
public record AdminBoard(
  EventStateView state,
  List<AdminRoomCard> rooms,
  List<RotationRow> rotation,
  List<RankingRow> ranking,
  List<AdminAlert> alerts,
  List<String> startWarnings,
  List<TeamFlagsView> teamFlags,
  List<OverrideAuditView> recentOverrides
) {}
