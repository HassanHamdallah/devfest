package dev.devfest.event.repository;

import dev.devfest.event.domain.ClueOverride;
import java.time.Instant;
import java.util.UUID;

/** The current clue verdict of one submission, with the submission's team and room. */
public record ClueVerdictRecord(
  UUID submissionId,
  long teamId,
  long roomId,
  boolean autoCorrect,
  ClueOverride override,
  boolean effectiveCorrect,
  Long overrideBy,
  String overrideByName,
  Instant overrideAt,
  long version
) {}
