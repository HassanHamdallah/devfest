package dev.devfest.event.dto;

import java.time.Instant;

/** The round countdown inputs; clients correct their own clock with {@code serverNow}. */
public record RoundClock(int currentRound, Instant roundStartedAt, int roundMinutes, Instant serverNow) {}
