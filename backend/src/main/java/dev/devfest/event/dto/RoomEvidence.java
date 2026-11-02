package dev.devfest.event.dto;

import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.domain.RoomCode;

/**
 * What the team may look back at for one room. While the room is not finished for the team only {@code roomCode},
 * {@code roomName}, {@code activity}, {@code finished} (false) and {@code clueCollected} are set. Once finished: the
 * team's latest technical output and clue answer with its effective result (all null when it never submitted there),
 * and {@code clueValue}, the room's current official clue, which is non-null only when this team collected that
 * clue (EvidenceVisibility). A room with an activity (AI quiz) never sends the output, answer or result: the team
 * sees only whether the clue was unlocked; {@code resultRecorded} says whether its result exists yet.
 */
public record RoomEvidence(
  RoomCode roomCode,
  String roomName,
  RoomActivity activity,
  boolean finished,
  String technicalOutput,
  String clueAnswer,
  Boolean clueCorrect,
  boolean clueCollected,
  String clueValue,
  boolean resultRecorded
) {}
