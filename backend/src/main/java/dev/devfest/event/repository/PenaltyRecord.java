package dev.devfest.event.repository;

import dev.devfest.event.domain.PenaltyKind;
import dev.devfest.event.domain.PenaltyRules;
import java.time.Instant;

/** One penalty with creator and voider names; {@code roomId} is null for WRONG_ACCUSATION. */
public record PenaltyRecord(
  long id,
  long teamId,
  Long roomId,
  PenaltyKind kind,
  int minutes,
  String note,
  long createdBy,
  String createdByName,
  Instant createdAt,
  Long voidedBy,
  String voidedByName,
  Instant voidedAt,
  String voidReason
) implements PenaltyRules.CountedPenalty {
  @Override
  public boolean voided() {
    return voidedAt != null;
  }
}
