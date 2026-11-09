package dev.devfest.event.dto;

import dev.devfest.event.domain.RoundPhase;
import java.time.Instant;

/** The final's one timer as the admin sees it. */
public record FinalTimerView(boolean open, RoundPhase phase, Integer remainingSeconds, Integer durationSeconds, Instant serverNow) {}
