package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.time.Instant;
import java.util.List;

/**
 * The admin's ranking row; {@code rank}, {@code finishedAt} and {@code adjustedSeconds} stay null until a team
 * finishes. {@code clueRooms} names the rooms whose clue the team holds (room position order); admin-only, the
 * public ranking keeps just the count.
 */
public record RankingRow(
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
  boolean disqualified
) {}
