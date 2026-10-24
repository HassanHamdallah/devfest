package dev.devfest.event.repository;

import dev.devfest.event.domain.RoomCode;
import java.time.Instant;
import java.util.UUID;

/**
 * One immutable room_submission row; {@code roomCode} is joined from event_room. {@code clueCorrect} is the
 * result at submission time (history); {@code effectiveCorrect} is the current verdict after key changes and
 * overrides.
 */
public record RoomSubmissionRecord(
  UUID id,
  long teamId,
  long roomId,
  RoomCode roomCode,
  int round,
  String technicalOutput,
  String clueAnswer,
  boolean clueCorrect,
  Instant receivedAt,
  UUID idempotencyKey,
  String requestHash,
  boolean effectiveCorrect
) {}
