package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.RoundPhase;
import dev.devfest.event.domain.RoundTimer;
import dev.devfest.event.dto.FinalTimerView;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.FinalAdjustmentRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin's emergency controls for the final's one timer: pause, resume and added time. They apply to every team at
 * once, are audited, and never touch one team alone; the timer is never reset or restarted. Once the time has run out
 * the final is over and the timer can no longer be changed (teams are finalised against that moment). All the arithmetic
 * is {@link RoundTimer}.
 */
@Service
public class EventFinalTimerService {

  /** Added time per request: 1 to 60 minutes. */
  static final int MAX_ADD_SECONDS = 60 * 60;

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final FinalAdjustmentRepository adjustments;
  private final AuditLog audit;
  private final EventClock clock;

  public EventFinalTimerService(
    EventAccessPolicy access,
    EventStateRepository states,
    FinalAdjustmentRepository adjustments,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.adjustments = adjustments;
    this.audit = audit;
    this.clock = clock;
  }

  public FinalTimerView view(Principal principal) {
    access.requireAdmin(principal);
    return view(states.state(), clock.now());
  }

  @Transactional
  public FinalTimerView pause(Principal principal) {
    return transition(principal, RoundTimer::pause, "EVENT_FINAL_PAUSED", "");
  }

  @Transactional
  public FinalTimerView resume(Principal principal) {
    return transition(principal, RoundTimer::resume, "EVENT_FINAL_RESUMED", "");
  }

  /** Adding time is not idempotent by itself, so the request carries an Idempotency-Key. */
  @Transactional
  public FinalTimerView addTime(Principal principal, int seconds, UUID idempotencyKey) {
    access.requireAdmin(principal);
    if (seconds < 1 || seconds > MAX_ADD_SECONDS) throw fail(400, "Add from 1 second to 60 minutes.");
    EventStateRecord state = states.lockForUpdate();
    Instant now = clock.now();
    RoundTimer current = requireOpenTimer(state, now);
    if (!adjustments.record(idempotencyKey, seconds, principal.id(), now)) return view(state, now);
    RoundTimer next = current.addSeconds(seconds, now);
    return save(principal, state, next, now, "EVENT_FINAL_TIME_ADDED", " +" + seconds + "s");
  }

  private FinalTimerView transition(
    Principal principal,
    java.util.function.BiFunction<RoundTimer, Instant, RoundTimer> step,
    String action,
    String detail
  ) {
    access.requireAdmin(principal);
    EventStateRecord state = states.lockForUpdate();
    Instant now = clock.now();
    RoundTimer current = requireOpenTimer(state, now);
    RoundTimer next;
    try {
      next = step.apply(current, now);
    } catch (RoundTimer.IllegalTransition e) {
      throw fail(409, e.getMessage());
    }
    if (next.equals(current)) return view(state, now);
    return save(principal, state, next, now, action, detail);
  }

  private FinalTimerView save(Principal principal, EventStateRecord state, RoundTimer next, Instant now, String action, String detail) {
    states.saveFinalTimer(next);
    states.bumpRevision();
    audit.record(principal.id(), action, "final" + detail);
    return view(states.state(), now);
  }

  private static RoundTimer requireOpenTimer(EventStateRecord state, Instant now) {
    if (!state.finalOpen()) throw fail(409, "The final is not open.");
    RoundTimer timer = state.finalTimer().orElseThrow(() -> fail(409, "The final is not open."));
    if (timer.effectivePhase(now) == RoundPhase.TIME_UP) throw fail(409, "Time is up for the final; its timer can no longer change.");
    return timer;
  }

  private static FinalTimerView view(EventStateRecord state, Instant now) {
    var timer = state.finalTimer();
    return new FinalTimerView(
      state.finalOpen(),
      timer.map(t -> t.effectivePhase(now)).orElse(null),
      timer.map(t -> t.remainingSeconds(now)).orElse(null),
      timer.map(RoundTimer::durationSeconds).orElse(null),
      now
    );
  }
}
