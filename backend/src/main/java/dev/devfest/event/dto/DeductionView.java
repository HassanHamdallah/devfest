package dev.devfest.event.dto;

import dev.devfest.event.domain.DeductionKind;
import dev.devfest.event.domain.RoomCode;
import java.time.Instant;

/** One deduction log row; {@code cancelled*} are null while it is active. Organizers and the admin only. */
public record DeductionView(
  long id,
  long teamId,
  String teamName,
  long roomId,
  RoomCode roomCode,
  DeductionKind kind,
  String reason,
  String createdByName,
  Instant createdAt,
  boolean cancelled,
  String cancelledByName,
  Instant cancelledAt,
  String cancelReason
) {}
