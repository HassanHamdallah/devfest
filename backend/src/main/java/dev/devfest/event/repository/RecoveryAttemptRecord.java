package dev.devfest.event.repository;

import java.time.Instant;
import java.util.UUID;

/** One immutable recovery attempt for the team's missing room. */
public record RecoveryAttemptRecord(
  UUID id,
  long teamId,
  long roomId,
  String answer,
  boolean correct,
  Instant receivedAt,
  UUID idempotencyKey,
  String requestHash
) {}
