package dev.devfest.event.repository;

import dev.devfest.event.domain.AnswerNormalizer;
import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.domain.RoomCode;

/**
 * One event_room row including its secrets (clue key, recovery answer); never expose it to teams.
 * {@code terminalEnabled}: the room offers the in-browser terminal (admin setting). {@code activity}: the team
 * activity that replaces the answer form (admin setting; null = none); {@code quizMcqMinutes}: the AI quiz's
 * multiple-choice cutoff in running minutes; {@code quizPassScore}: the total a team needs for the room's clue.
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
  boolean terminalEnabled,
  RoomActivity activity,
  int quizMcqMinutes,
  int quizPassScore
) {
  public boolean hasQuiz() {
    return activity == RoomActivity.AI_QUIZ;
  }

  /** A key that normalizes to nothing can never be matched, so it counts as missing. */
  public boolean hasClueKey() {
    return !AnswerNormalizer.normalize(clueKey).isEmpty();
  }

  public boolean hasRecoveryChallenge() {
    return !recoveryPrompt.isBlank() && !AnswerNormalizer.normalize(recoveryAnswer).isEmpty();
  }
}
