package dev.devfest.event.dto;

import dev.devfest.event.domain.RoomCode;

/**
 * What the team may look back at for one room. While the room is not finished for the team only {@code roomCode},
 * {@code roomName}, {@code finished} (false) and {@code clueCollected} are set. Once finished: the team's latest technical output and
 * clue answer with its effective result (all null when it never submitted there), and {@code clueValue}, the
 * room's current official clue, which is non-null only when this team collected that clue (EvidenceVisibility).
 */
public record RoomEvidence(
  RoomCode roomCode,
  String roomName,
  boolean finished,
  String technicalOutput,
  String clueAnswer,
  Boolean clueCorrect,
  boolean clueCollected,
  String clueValue
) {}
