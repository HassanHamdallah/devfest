package dev.devfest.event.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A team's locked order for one repair (snippet ids as submitted) and its partial-credit score. */
public record QuizRepairAnswerRecord(
  long teamId,
  long repairId,
  long roomId,
  int round,
  List<Long> snippetOrder,
  int correctPositions,
  int snippetCount,
  BigDecimal pointsEarned,
  Instant submittedAt,
  UUID idempotencyKey,
  String requestHash
) {}
