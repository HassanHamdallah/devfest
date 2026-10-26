package dev.devfest.event.repository;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.time.Instant;
import java.util.List;

/**
 * One active team in ranking order. {@code rank}, {@code finishedAt} and {@code adjustedSeconds}
 * are null for teams that have not finished. Penalty figures count active (non-voided) penalties only.
 * {@code clueRooms} lists the rooms whose clue the team holds, in room position order.
 */
public record RankedTeamRecord(
  Integer rank,
  long teamId,
  String teamName,
  GroupCode groupCode,
  int clues,
  List<RoomCode> clueRooms,
  int penaltyMinutes,
  int penaltyEntries,
  boolean finished,
  Instant finishedAt,
  Long adjustedSeconds,
  boolean recoveryUsed,
  int accusationAttempts,
  boolean disqualified
) {}
