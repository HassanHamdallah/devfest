package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.event.domain.RoundPhase;
import dev.devfest.event.domain.RoundTimer;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.TeamFinalRepository;
import dev.devfest.event.repository.TeamFinalRepository.ClueFacts;
import dev.devfest.event.repository.TeamFinalRepository.Row;
import dev.devfest.event.service.TeamWriteGate.TeamWrite;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The checks every write of a team's final shares (case and recovery alike), in order, after {@link TeamWriteGate}: the
 * final is open, the team's final is not over, the timer has time left (a write that finds none finalises the team
 * first and is refused with {@link EventFinalService.FinalTimeUp}) and is not paused. Every team plays, whatever its keys.
 */
@Component
class FinalWriteGuard {

  /** What every write learns after the gate: the locked state, the team's facts and row, the timer and the time. */
  record Guard(EventStateRecord state, ClueFacts facts, Row row, RoundTimer timer, Instant now) {}

  private final TeamFinalRepository finals;
  private final EventStateRepository states;
  private final EventClock clock;

  FinalWriteGuard(TeamFinalRepository finals, EventStateRepository states, EventClock clock) {
    this.finals = finals;
    this.states = states;
    this.clock = clock;
  }

  Guard check(TeamWrite write) {
    EventStateRecord state = write.state();
    if (!state.finalOpen()) throw fail(409, "The final is not open.");
    long teamId = write.team().id();
    Instant now = clock.now();
    ClueFacts facts = finals.clueFacts(teamId);
    Row row = finals.find(teamId).orElse(null);
    if (row != null && row.finalised()) throw fail(409, "Your final is already over.");
    RoundTimer timer = state.finalTimer().orElseThrow(() -> fail(409, "The final is not open."));
    RoundPhase phase = timer.effectivePhase(now);
    if (phase == RoundPhase.TIME_UP) {
      finals.finalise(teamId, "TIME_UP", now);
      states.bumpRevision();
      throw new EventFinalService.FinalTimeUp();
    }
    if (phase == RoundPhase.PAUSED) throw fail(409, "The final is paused.");
    return new Guard(state, facts, row, timer, now);
  }
}
