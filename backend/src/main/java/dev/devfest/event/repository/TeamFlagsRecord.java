package dev.devfest.event.repository;

import dev.devfest.event.domain.RoomCode;

/**
 * Override markers of one active team: disqualification, this round's room exception (null if none), manual
 * clues held, and the sum of active time adjustments in minutes.
 */
public record TeamFlagsRecord(
  long teamId,
  String teamName,
  boolean disqualified,
  RoomCode exceptionRoom,
  int manualClues,
  int timeAdjustmentMinutes
) {}
