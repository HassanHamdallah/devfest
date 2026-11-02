package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.List;

/**
 * The team home screen. {@code scheduledRoom} is null while no round is running; {@code evidence} has one entry
 * per room (see {@link RoomEvidence}).
 */
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
  long revision,
  boolean disqualified,
  List<RoomEvidence> evidence
) {
  /**
   * {@code exception}: the admin sent the team to this room for this round instead of its rotation room.
   * {@code terminalEnabled}: the room offers the in-browser terminal (its files come from a separate endpoint).
   */
  public record ScheduledRoom(
    long roomId,
    RoomCode code,
    String name,
    boolean open,
    boolean exception,
    boolean terminalEnabled
  ) {}
}
