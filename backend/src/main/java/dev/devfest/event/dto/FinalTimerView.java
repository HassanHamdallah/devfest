package dev.devfest.event.dto;

import dev.devfest.event.domain.RoundPhase;
import java.time.Instant;

/**
 * The final's one timer as the admin sees it. {@code phase1Minutes} is the content's estimate of the decryption phase
 * (it closes nothing: a team opens its investigation by decrypting all four files, see FinalPhase).
 */
public record FinalTimerView(
  boolean open,
  RoundPhase phase,
  Integer remainingSeconds,
  Integer durationSeconds,
  Instant serverNow,
  int phase1Minutes
) {}
