package dev.devfest.event.repository;

import java.time.Instant;

/** The single event_state row. */
public record EventStateRecord(
  int currentRound,
  Instant roundStartedAt,
  int roundMinutes,
  boolean finalOpen,
  Instant finalStartedAt,
  Instant finalClosedAt,
  boolean resultsPublished,
  long revision,
  Instant updatedAt
) {
  public boolean finalEverOpened() {
    return finalStartedAt != null;
  }
}
