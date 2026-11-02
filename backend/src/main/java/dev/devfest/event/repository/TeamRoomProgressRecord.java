package dev.devfest.event.repository;

import dev.devfest.event.domain.JudgeStatus;
import dev.devfest.event.domain.RoomCode;
import java.time.Instant;
import java.util.UUID;

/**
 * One room as seen by one team: public room fields plus that team's own progress. Deliberately carries
 * no clue key or recovery data. The {@code last*} fields are null when the team never submitted there.
 */
public record TeamRoomProgressRecord(
  long roomId,
  RoomCode code,
  String name,
  int position,
  boolean open,
  JudgeStatus judgement,
  boolean clueCollected,
  UUID lastSubmissionId,
  Instant lastReceivedAt,
  Boolean lastClueCorrect,
  Boolean lastEffectiveCorrect
) {
  public boolean hasSubmission() {
    return lastSubmissionId != null;
  }
}
