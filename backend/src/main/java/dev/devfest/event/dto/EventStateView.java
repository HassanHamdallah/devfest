package dev.devfest.event.dto;

import java.time.Instant;

/** The event_state row as the admin sees it, with the server time for the countdown. */
public record EventStateView(
  int currentRound,
  Instant roundStartedAt,
  int roundMinutes,
  Instant serverNow,
  boolean finalOpen,
  Instant finalStartedAt,
  Instant finalClosedAt,
  boolean resultsPublished,
  long revision
) {}
