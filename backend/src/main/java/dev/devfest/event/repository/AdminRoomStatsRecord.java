package dev.devfest.event.repository;

import dev.devfest.event.domain.RoomCode;

/**
 * A room card for the admin board. Counts cover active teams only; {@code pending} counts teams that
 * submitted at least once and have no PASS/FAIL yet.
 */
public record AdminRoomStatsRecord(
  long id,
  RoomCode code,
  String name,
  int position,
  boolean open,
  Long managerId,
  String managerName,
  boolean hasClueKey,
  boolean hasRecoveryChallenge,
  int passed,
  int failed,
  int pending
) {}
