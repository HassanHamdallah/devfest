package dev.devfest.event.service;

import dev.devfest.event.domain.FinalPhase;
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

  /** The phase the final is in now, the same for every team (see {@link FinalPhase}). */
  static FinalPhase phase(EventStateRecord state, int phase1Minutes, Instant now) {
    return state.finalTimer().map(timer -> FinalPhase.at(activeSeconds(timer, now), phase1Minutes)).orElse(FinalPhase.DECRYPTION);
  }

  /** Active seconds until the investigation phase opens; null once open (or before the final has a timer). */
  static Integer phase1EndsInSeconds(EventStateRecord state, int phase1Minutes, Instant now) {
    return state.finalTimer().map(timer -> FinalPhase.phase1EndsInSeconds(activeSeconds(timer, now), phase1Minutes)).orElse(null);
  }
}
