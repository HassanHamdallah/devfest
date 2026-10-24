package dev.devfest.event.repository;

import dev.devfest.event.domain.JudgeStatus;
import java.time.Instant;

/** Current judgement of a team in a room; version 0 means no row exists yet. */
public record RoomJudgementRecord(
  long teamId,
  long roomId,
  JudgeStatus status,
  int hints,
  String note,
  long version,
  Long reviewerId,
  String reviewerName,
  Instant updatedAt
) {
  public static RoomJudgementRecord pending(long teamId, long roomId) {
    return new RoomJudgementRecord(teamId, roomId, JudgeStatus.PENDING, 0, "", 0, null, null, null);
  }

  public boolean exists() {
    return version > 0;
  }

  public boolean sameDecision(JudgeStatus otherStatus, int otherHints, String otherNote) {
    return status == otherStatus && hints == otherHints && note.equals(otherNote);
  }
}
