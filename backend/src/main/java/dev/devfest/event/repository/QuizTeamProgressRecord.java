package dev.devfest.event.repository;

import java.time.Instant;

/** A team's AI room state: when it first acted in the quiz and when it pressed "Finish MCQs". */
public record QuizTeamProgressRecord(long teamId, long roomId, Instant createdAt, Instant mcqFinishedAt) {
  public boolean mcqFinished() {
    return mcqFinishedAt != null;
  }
}
