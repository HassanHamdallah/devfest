package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.RoomCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The team's final screen. Stage-specific parts are null (or empty) outside their stage; nothing here can
 * carry a recovery answer or the correct suspect. {@code evidence} is the same per-room list as on the team board.
 */
public record TeamFinal(
  FinalStage stage,
  Instant finalStartedAt,
  Instant serverNow,
  int cluesCollected,
  RecoveryChallenge recovery,
  String encryptedMessage,
  List<FinalSuspect> suspects,
  int accusationAttempts,
  int wrongAccusations,
  int penaltyMinutes,
  TeamFinish finish,
  boolean resultsPublished,
  List<RoomEvidence> evidence
) {
  public record RecoveryChallenge(long roomId, RoomCode roomCode, String roomName, String prompt, int attempts) {}

  public record FinalSuspect(long id, String name, String description) {}

  public record TeamFinish(Instant finishedAt, long adjustedSeconds, UUID receiptId, String suspectName) {}
}
