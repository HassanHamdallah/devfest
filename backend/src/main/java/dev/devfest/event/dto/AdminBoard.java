package dev.devfest.event.dto;

import java.util.List;

/**
 * The admin control screen. {@code startWarnings}: closed rooms and rooms without a clue key (not blocking).
 * {@code startBlockers}: AI quiz rooms that cannot run (no clue key, no questions); the timer starts only with
 * "Start anyway" while any is listed.
 */
public record AdminBoard(
  EventStateView state,
  List<AdminRoomCard> rooms,
  List<RotationRow> rotation,
  List<RankingRow> ranking,
  List<AdminAlert> alerts,
  List<String> startWarnings,
  List<String> startBlockers,
  List<TeamFlagsView> teamFlags,
  List<OverrideAuditView> recentOverrides
) {}
