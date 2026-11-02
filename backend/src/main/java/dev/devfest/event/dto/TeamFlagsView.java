package dev.devfest.event.dto;

import dev.devfest.event.domain.RoomCode;

/** A team's override markers for the admin; {@code exceptionRoom} is this round's room exception (or null). */
public record TeamFlagsView(
  long teamId,
  String teamName,
  boolean disqualified,
  RoomCode exceptionRoom,
  int manualClues,
  int timeAdjustmentMinutes
) {}
