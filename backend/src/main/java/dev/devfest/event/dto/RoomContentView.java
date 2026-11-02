package dev.devfest.event.dto;

import dev.devfest.event.domain.RoomCode;

/** A room's secret content; only for the room's manager and ADMIN. */
public record RoomContentView(
  long roomId,
  RoomCode code,
  String name,
  String clueKey,
  String recoveryPrompt,
  String recoveryAnswer,
  long version
) {}
