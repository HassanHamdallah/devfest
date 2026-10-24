package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.List;

/** The team home screen. {@code scheduledRoom} is null while no round is running. */
public record TeamBoard(
  String teamName,
  GroupCode groupCode,
  RoundClock clock,
  ScheduledRoom scheduledRoom,
  List<TeamRoomCard> rooms,
  int cluesCollected,
  FinalStage finalStage,
  boolean finalOpen,
  boolean resultsPublished,
  long revision
) {
  public record ScheduledRoom(long roomId, RoomCode code, String name, boolean open) {}
}
