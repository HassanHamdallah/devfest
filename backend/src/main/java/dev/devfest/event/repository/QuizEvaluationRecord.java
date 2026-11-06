package dev.devfest.event.repository;

import dev.devfest.event.domain.QuizEvaluationCause;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A team's one evaluation in an AI room: its total against the pass score in force at that moment, and the room
 * submission that recorded the result. {@code clueCollected}: whether the team holds the room's clue now (any
 * source); filled by reads only.
 */
public record QuizEvaluationRecord(
  long teamId,
  long roomId,
  int round,
  UUID submissionId,
  BigDecimal total,
  int passScore,
  boolean passed,
  QuizEvaluationCause cause,
  Instant evaluatedAt,
  boolean clueCollected
) {}
