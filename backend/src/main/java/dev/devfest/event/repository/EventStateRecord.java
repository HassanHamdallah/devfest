package dev.devfest.event.repository;

import dev.devfest.event.domain.RoundPhase;
import dev.devfest.event.domain.RoundTimer;
import java.time.Instant;
import java.util.Optional;

/** The single event_state row. The round_* timer fields are set only while a round (1..4) runs. */
public record EventStateRecord(
  int currentRound,
  Instant roundStartedAt,
  int roundMinutes,
  boolean finalOpen,
  Instant finalStartedAt,
  Instant finalClosedAt,
  boolean resultsPublished,
  long revision,
  Instant updatedAt,
  int plannedTeamCount,
  RoundPhase roundPhase,
  Integer roundDurationSeconds,
  int roundElapsedBeforeSeconds,
  Instant roundResumedAt,
  boolean roundEnded
) {
  /** The running round's timer; empty in rounds 0 and 5. */
  public Optional<RoundTimer> timer() {
    if (roundPhase == null) return Optional.empty();
    return Optional.of(
      new RoundTimer(roundPhase, roundDurationSeconds, roundElapsedBeforeSeconds, roundResumedAt, roundEnded)
    );
  }

  public boolean finalEverOpened() {
    return finalStartedAt != null;
  }
}
