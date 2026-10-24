package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.RoundPhase;
import dev.devfest.event.domain.RoundTimer;
import dev.devfest.event.dto.EventStateView;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.RoundAdjustmentRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin's manual round timer: start, pause, resume, add or remove time, end the round. All math lives in
 * {@link RoundTimer}; this service locks event_state, stores the transition, audits it and bumps the revision.
 * A transition into the state the timer is already in (a double click) returns 200 without a write.
 */
@Service
public class EventRoundTimerService {

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final RoundAdjustmentRepository adjustments;
  private final AuditLog audit;
  private final EventClock clock;

  public EventRoundTimerService(
    EventAccessPolicy access,
    EventStateRepository states,
    RoundAdjustmentRepository adjustments,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.adjustments = adjustments;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional
  public EventStateView start(Principal principal) {
    return transition(principal, RoundTimer::start, "EVENT_TIMER_STARTED");
  }

  @Transactional
  public EventStateView pause(Principal principal) {
    return transition(principal, RoundTimer::pause, "EVENT_TIMER_PAUSED");
  }

  @Transactional
  public EventStateView resume(Principal principal) {
    return transition(principal, RoundTimer::resume, "EVENT_TIMER_RESUMED");
  }

  @Transactional
  public EventStateView end(Principal principal) {
    return transition(principal, RoundTimer::endNow, "EVENT_ROUND_ENDED");
  }

  /**
   * Adding time is not idempotent, so the client sends an Idempotency-Key: the key is stored with the round in
   * round_time_adjustment (primary key round + key), and a repeated key in the same round changes nothing.
   */
  @Transactional
  public EventStateView adjust(Principal principal, int deltaSeconds, UUID idempotencyKey) {
    access.requireAdmin(principal);
    if (deltaSeconds == 0) throw fail(400, "Add or remove at least one second.");
    EventStateRecord state = states.lockForUpdate();
    Instant now = clock.now();
    requireTimer(state);
    if (!adjustments.record(state.currentRound(), idempotencyKey, deltaSeconds, principal.id(), now)) {
      return EventViews.eventState(state, now);
    }
    BiFunction<RoundTimer, Instant, RoundTimer> step = deltaSeconds > 0
      ? (timer, at) -> timer.addSeconds(deltaSeconds, at)
      : (timer, at) -> timer.removeSeconds(-deltaSeconds, at);
    String action = deltaSeconds > 0 ? "EVENT_TIME_ADDED" : "EVENT_TIME_REMOVED";
    return apply(principal, state, now, step, action, " " + (deltaSeconds > 0 ? "+" : "") + deltaSeconds + "s");
  }

  private EventStateView transition(
    Principal principal,
    BiFunction<RoundTimer, Instant, RoundTimer> step,
    String action
  ) {
    access.requireAdmin(principal);
    EventStateRecord state = states.lockForUpdate();
    return apply(principal, state, clock.now(), step, action, "");
  }

  private EventStateView apply(
    Principal principal,
    EventStateRecord state,
    Instant now,
    BiFunction<RoundTimer, Instant, RoundTimer> step,
    String action,
    String detail
  ) {
    RoundTimer current = requireTimer(state);
    RoundTimer next;
    try {
      next = step.apply(current, now);
    } catch (RoundTimer.IllegalTransition e) {
      throw fail(409, e.getMessage());
    }
    if (next.equals(current)) return EventViews.eventState(state, now);
    states.saveTimer(next, next.phase() == RoundPhase.ASSIGNED ? null : now);
    states.bumpRevision();
    audit.record(principal.id(), action, "round:" + state.currentRound() + detail);
    return EventViews.eventState(states.state(), now);
  }

  private static RoundTimer requireTimer(EventStateRecord state) {
    return state.timer().orElseThrow(() -> fail(409, "No round is running."));
  }
}
