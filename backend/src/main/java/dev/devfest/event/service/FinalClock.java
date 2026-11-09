package dev.devfest.event.service;

import dev.devfest.event.domain.RoundPhase;
import dev.devfest.event.domain.RoundTimer;
import dev.devfest.event.repository.EventStateRecord;
import java.time.Instant;

/** Questions about the final's one timer, answered from the stored state and the server clock only. */
final class FinalClock {

  private FinalClock() {}

  /** The final has been opened and its timer shows no time left (it ran out, or the admin closed the final). */
  static boolean timeUp(EventStateRecord state, Instant now) {
    return state.finalTimer().map(timer -> timer.effectivePhase(now) == RoundPhase.TIME_UP).orElse(false);
  }

  /** Active seconds the final has run so far (pauses excluded), at most its duration. */
  static int activeSeconds(RoundTimer timer, Instant now) {
    return timer.usedSeconds(now);
  }
}
