package dev.devfest.event.repository;

import dev.devfest.event.domain.PointsLedger;
import java.time.Instant;
import java.util.UUID;

/** One row of the points ledger ({@code undo}: it reverses {@code reversesId}); {@code enteredByName} is for the history only. */
public record PointsEntryRecord(
  long id,
  long teamId,
  long roomId,
  boolean undo,
  int delta,
  int totalAfter,
  Long reversesId,
  UUID idempotencyKey,
  long enteredBy,
  String enteredByName,
  Instant enteredAt
) {
  public PointsLedger.Entry entry() {
    return new PointsLedger.Entry(id, delta, reversesId);
  }
}
