package dev.devfest.event.repository;

import dev.devfest.event.domain.JudgeStatus;
import dev.devfest.event.domain.RoomCode;
import java.time.Instant;
import java.util.UUID;

/**
 * One room as seen by one team: public room fields plus that team's own progress and latest submission. Carries
 * no recovery data; {@code collectedClueKey} is the room's current key only when this team collected its clue
 * (null otherwise). The {@code last*} fields are null when the team never submitted there.
 */
public record TeamRoomProgressRecord(
  long roomId,
  RoomCode code,
  String name,
  int position,
  boolean open,
  boolean terminalEnabled,
  JudgeStatus judgement,
  boolean clueCollected,
  String collectedClueKey,
  UUID lastSubmissionId,
  Instant lastReceivedAt,
  Boolean lastClueCorrect,
  Boolean lastEffectiveCorrect,
  String lastTechnicalOutput,
  String lastClueAnswer
) {
  public boolean hasSubmission() {
    return lastSubmissionId != null;
  }
}
