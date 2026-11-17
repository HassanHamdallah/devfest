package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalPhase;
import dev.devfest.event.domain.RoundPhase;
import java.time.Instant;

/**
 * The final's one timer as the admin sees it. {@code finalPhase} is the phase every team is in now, {@code phase1Minutes}
 * the length of the decryption phase and {@code phase1EndsInSeconds} the active seconds until the investigation phase
 * opens (null once open or before the timer runs).
 */
public record FinalTimerView(
  boolean open,
  RoundPhase phase,
  Integer remainingSeconds,
  Integer durationSeconds,
  Instant serverNow,
  FinalPhase finalPhase,
  int phase1Minutes,
  Integer phase1EndsInSeconds
) {}
