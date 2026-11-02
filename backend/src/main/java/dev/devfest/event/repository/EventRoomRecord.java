package dev.devfest.event.repository;

import dev.devfest.event.domain.AnswerNormalizer;
import dev.devfest.event.domain.RoomCode;

/**
 * One event_room row including its secrets (clue key, recovery answer); never expose it to teams.
 * {@code terminalEnabled}: the room offers the in-browser terminal (admin setting).
 */
public record EventRoomRecord(
  long id,
  RoomCode code,
  String name,
  int position,
  Long managerId,
  boolean open,
  String clueKey,
  String recoveryPrompt,
  String recoveryAnswer,
  long version,
  boolean terminalEnabled
) {
  /** A key that normalizes to nothing can never be matched, so it counts as missing. */
  public boolean hasClueKey() {
    return !AnswerNormalizer.normalize(clueKey).isEmpty();
  }

  public boolean hasRecoveryChallenge() {
    return !recoveryPrompt.isBlank() && !AnswerNormalizer.normalize(recoveryAnswer).isEmpty();
  }
}
