package dev.devfest.event.dto;

import dev.devfest.event.domain.RoomCode;

/**
 * A room's secret content; only for the room's manager and ADMIN. {@code recheckedSubmissions} is how many
 * answers a save re-checked against a changed clue key (0 when the key did not change, and in content lists).
 * {@code terminalEnabled} is the admin's per-room terminal setting (not versioned with the content).
 */
public record RoomContentView(
  long roomId,
  RoomCode code,
  String name,
  String clueKey,
  String recoveryPrompt,
  String recoveryAnswer,
  long version,
  int recheckedSubmissions,
  boolean terminalEnabled
) {}
