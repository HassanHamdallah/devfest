package dev.devfest.event.dto;

import dev.devfest.event.domain.RoundPhase;
import java.time.Instant;

/**
 * The event_state row as the admin sees it, with the round countdown computed at {@code serverNow} (same fields
 * as {@link RoundClock}). {@code roundMinutes} is the default length of rounds that begin later.
 */
public record EventStateView(
  int currentRound,
  RoundPhase phase,
  Integer remainingSeconds,
  Integer durationSeconds,
  Instant serverNow,
  Instant roundStartedAt,
  int roundMinutes,
  boolean finalOpen,
  Instant finalStartedAt,
  Instant finalClosedAt,
  boolean resultsPublished,
  long revision
) {}
