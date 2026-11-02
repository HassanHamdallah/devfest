package dev.devfest.event.repository;

import java.time.Instant;
import java.util.UUID;

/** One immutable accusation. */
public record AccusationRecord(
  UUID id,
  long teamId,
  long suspectId,
  String decryptedMessage,
  String evidence,
  boolean correct,
  Instant receivedAt,
  UUID idempotencyKey,
  String requestHash
) {}
