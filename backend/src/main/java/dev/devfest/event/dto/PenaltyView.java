package dev.devfest.event.dto;

import dev.devfest.event.domain.PenaltyKind;
import java.time.Instant;

/** {@code roomId} is null for WRONG_ACCUSATION; the void fields are null while the penalty is active. */
public record PenaltyView(
  long id,
  long teamId,
  Long roomId,
  PenaltyKind kind,
  int minutes,
  String note,
  String createdByName,
  Instant createdAt,
  boolean voided,
  String voidedByName,
  Instant voidedAt,
  String voidReason
) {}
