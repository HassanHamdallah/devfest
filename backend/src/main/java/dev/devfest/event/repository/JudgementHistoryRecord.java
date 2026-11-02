package dev.devfest.event.repository;

import dev.devfest.event.domain.JudgeStatus;
import java.time.Instant;

/** One immutable judgement change; {@code oldStatus} is null for the first judgement. */
public record JudgementHistoryRecord(
  long id,
  long teamId,
  long roomId,
  JudgeStatus oldStatus,
  JudgeStatus newStatus,
  int hints,
  String note,
  long reviewerId,
  String reviewerName,
  Instant changedAt,
  long version
) {}
