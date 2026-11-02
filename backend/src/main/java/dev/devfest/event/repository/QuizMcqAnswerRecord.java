package dev.devfest.event.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A team's locked answer to one question; {@code pointsEarned} was fixed by QuizScoring when it was stored. */
public record QuizMcqAnswerRecord(
  long teamId,
  long questionId,
  long optionId,
  long roomId,
  int round,
  BigDecimal pointsEarned,
  Instant answeredAt,
  UUID idempotencyKey,
  String requestHash
) {}
