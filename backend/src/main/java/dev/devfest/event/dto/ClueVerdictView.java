package dev.devfest.event.dto;

import dev.devfest.event.domain.ClueOverride;
import java.time.Instant;
import java.util.UUID;

/** A submission's clue verdict for the room's manager and ADMIN; never sent to teams. */
public record ClueVerdictView(
  UUID submissionId,
  long teamId,
  boolean autoCorrect,
  ClueOverride override,
  boolean effectiveCorrect,
  long version,
  String overrideByName,
  Instant overrideAt
) {}
