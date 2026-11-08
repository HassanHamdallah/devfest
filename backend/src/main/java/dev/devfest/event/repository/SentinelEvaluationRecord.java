package dev.devfest.event.repository;

import dev.devfest.event.domain.MissionEvaluationCause;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A team's one evaluation in an AI room: its total against the pass score in force at that moment, and the room
 * submission that recorded the result. {@code clueCollected}: whether the team holds the room's clue now (any
 * source); filled by reads only.
 */
public record SentinelEvaluationRecord(
  long teamId,
  long roomId,
  int round,
  UUID submissionId,
  BigDecimal total,
  int passScore,
  boolean passed,
  MissionEvaluationCause cause,
  Instant evaluatedAt,
  boolean clueCollected
) {}
