package dev.devfest.event.dto;

import java.util.List;

/** The admin control screen. */
public record AdminBoard(
  EventStateView state,
  List<AdminRoomCard> rooms,
  List<RotationRow> rotation,
  List<RankingRow> ranking,
  List<AdminAlert> alerts
) {}
