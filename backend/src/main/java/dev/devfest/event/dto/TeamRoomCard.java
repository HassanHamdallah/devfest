package dev.devfest.event.dto;

import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.TeamRoomStatus;

/** One room on the team home: public room fields and the team's own progress, never room secrets. */
public record TeamRoomCard(
  long roomId,
  RoomCode code,
  String name,
  int position,
  boolean open,
  int scheduledRound,
  boolean scheduledNow,
  TeamRoomStatus myStatus,
  boolean clueCollected,
  Receipt lastReceipt
) {}
