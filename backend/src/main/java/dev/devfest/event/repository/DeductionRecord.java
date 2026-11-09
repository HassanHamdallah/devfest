package dev.devfest.event.repository;

import dev.devfest.event.domain.DeductionKind;
import dev.devfest.event.domain.RoomCode;
import java.time.Instant;

/**
 * One row of the append-only deduction log: a hint (-2) or a cheating violation (-7.5) recorded for a team in a
 * room. {@code reason} is the optional hint note or the required cheating reason; a cancelled row stays in the
 * log with who cancelled it, when and why, and no longer counts.
 */
public record DeductionRecord(
  long id,
  long teamId,
  String teamName,
  long roomId,
  RoomCode roomCode,
  DeductionKind kind,
  String reason,
  String createdByName,
  Instant createdAt,
  String cancelledByName,
  Instant cancelledAt,
  String cancelReason
) {
  public boolean cancelled() {
    return cancelledAt != null;
  }
}
