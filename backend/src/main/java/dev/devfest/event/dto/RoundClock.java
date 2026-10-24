package dev.devfest.event.dto;

import dev.devfest.event.domain.RoundPhase;
import java.time.Instant;

/**
 * The round countdown as the server computes it. {@code phase} (incl. TIME_UP), {@code remainingSeconds} and
 * {@code durationSeconds} are null in rounds 0 and 5. Clients count down locally from {@code remainingSeconds},
 * correcting their own clock with {@code serverNow}; the timer itself never bumps the revision.
 */
public record RoundClock(
  int currentRound,
  RoundPhase phase,
  Integer remainingSeconds,
  Integer durationSeconds,
  Instant serverNow
) {}
