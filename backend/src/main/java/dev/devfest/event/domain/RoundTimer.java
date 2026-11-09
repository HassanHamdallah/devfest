package dev.devfest.event.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The countdown of one running round, computed from stored fields and the server clock (no scheduler). Whole
 * seconds throughout, never negative. Every transition returns a new timer; a transition into the state the
 * timer is already in returns {@code this}, so a repeated click changes nothing.
 *
 * @param phase stored phase: ASSIGNED, RUNNING or PAUSED (never TIME_UP)
 * @param durationSeconds total length of the round, including time added or removed
 * @param elapsedBeforeSeconds running time folded in by pauses (and by ending the round)
 * @param resumedAt when the timer last started or resumed; required while RUNNING
 * @param ended the admin ended the round: no time remains whatever the other fields say
 */
public record RoundTimer(
  RoundPhase phase,
  int durationSeconds,
  int elapsedBeforeSeconds,
  Instant resumedAt,
  boolean ended
) {

  /** No round may be longer than a day, whatever is added. */
  public static final int MAX_DURATION_SECONDS = 24 * 60 * 60;

  /** A transition the timer's current phase does not allow; the message is shown to the admin. */
  public static final class IllegalTransition extends RuntimeException {

    public IllegalTransition(String message) {
      super(message);
    }
  }

  public RoundTimer {
    Objects.requireNonNull(phase, "phase");
    if (!phase.stored()) throw new IllegalArgumentException("TIME_UP is derived, never stored");
    if (durationSeconds <= 0) throw new IllegalArgumentException("durationSeconds must be positive");
    if (elapsedBeforeSeconds < 0) throw new IllegalArgumentException("elapsedBeforeSeconds must not be negative");
    if (phase == RoundPhase.RUNNING && resumedAt == null) throw new IllegalArgumentException("RUNNING needs resumedAt");
  }

  /** A round that has just begun: teams know their rooms, the timer has not started. */
  public static RoundTimer assigned(int durationSeconds) {
    return new RoundTimer(RoundPhase.ASSIGNED, durationSeconds, 0, null, false);
  }

  public int remainingSeconds(Instant now) {
    if (ended) return 0;
    return (int) Math.max(0, durationSeconds - elapsedBeforeSeconds - runningSeconds(now));
  }

  public RoundPhase effectivePhase(Instant now) {
    if (phase == RoundPhase.ASSIGNED) return RoundPhase.ASSIGNED;
    return remainingSeconds(now) == 0 ? RoundPhase.TIME_UP : phase;
  }

  public RoundTimer start(Instant now) {
    return switch (effectivePhase(now)) {
      case ASSIGNED -> new RoundTimer(RoundPhase.RUNNING, durationSeconds, 0, now, false);
      case RUNNING -> this;
      case PAUSED -> throw new IllegalTransition("The timer is paused. Resume it instead.");
      case TIME_UP -> throw new IllegalTransition("Time is up for this round. Add time to continue.");
    };
  }

  public RoundTimer pause(Instant now) {
    return switch (effectivePhase(now)) {
      case RUNNING -> new RoundTimer(RoundPhase.PAUSED, durationSeconds, usedSeconds(now), null, false);
      case PAUSED -> this;
      case ASSIGNED -> throw new IllegalTransition("The timer has not started yet.");
      case TIME_UP -> throw new IllegalTransition("Time is up for this round.");
    };
  }

  public RoundTimer resume(Instant now) {
    return switch (effectivePhase(now)) {
      case PAUSED -> new RoundTimer(RoundPhase.RUNNING, durationSeconds, elapsedBeforeSeconds, now, false);
      case RUNNING -> this;
      case ASSIGNED -> throw new IllegalTransition("The timer has not started yet. Start it instead.");
      case TIME_UP -> throw new IllegalTransition("Time is up for this round. Add time to continue.");
    };
  }

  /**
   * More time. After time is up the round continues with exactly {@code seconds} left, running again if it was
   * running and still paused if it was paused.
   */
  public RoundTimer addSeconds(int seconds, Instant now) {
    requirePositive(seconds);
    if (effectivePhase(now) != RoundPhase.TIME_UP) return withDuration((long) durationSeconds + seconds);
    int used = usedSeconds(now);
    requireMaxDuration((long) used + seconds);
    Instant resumed = phase == RoundPhase.RUNNING ? now : null;
    return new RoundTimer(phase, used + seconds, used, resumed, false);
  }

  /** Less time; never below the time already used. Removing at least what remains ends the round (TIME_UP). */
  public RoundTimer removeSeconds(int seconds, Instant now) {
    requirePositive(seconds);
    return switch (effectivePhase(now)) {
      case ASSIGNED -> {
        if (durationSeconds - seconds < 1) throw new IllegalTransition("The round needs at least one second.");
        yield withDuration(durationSeconds - seconds);
      }
      case TIME_UP -> this;
      case RUNNING, PAUSED -> durationSeconds - seconds <= usedSeconds(now)
        ? endNow(now)
        : withDuration(durationSeconds - seconds);
    };
  }

  /** No time left from now on; the time used so far is kept so added time continues from here. */
  public RoundTimer endNow(Instant now) {
    return switch (effectivePhase(now)) {
      case RUNNING, PAUSED -> new RoundTimer(
        phase,
        durationSeconds,
        usedSeconds(now),
        phase == RoundPhase.RUNNING ? now : null,
        true
      );
      case TIME_UP -> this;
      case ASSIGNED -> throw new IllegalTransition("The timer has not started yet.");
    };
  }

  /** Time the round has actually run: at most its duration, frozen when it was ended. */
  public int usedSeconds(Instant now) {
    if (ended) return elapsedBeforeSeconds;
    return (int) Math.min(durationSeconds, elapsedBeforeSeconds + runningSeconds(now));
  }

  /** Whole seconds since the last start or resume while RUNNING; 0 otherwise (and if the clock went back). */
  private long runningSeconds(Instant now) {
    if (phase != RoundPhase.RUNNING) return 0;
    return Math.max(0, Duration.between(resumedAt, now).getSeconds());
  }

  private RoundTimer withDuration(long seconds) {
    requireMaxDuration(seconds);
    return new RoundTimer(phase, (int) seconds, elapsedBeforeSeconds, resumedAt, ended);
  }

  private static void requireMaxDuration(long seconds) {
    if (seconds > MAX_DURATION_SECONDS) throw new IllegalTransition("A round cannot be longer than 24 hours.");
  }

  private static void requirePositive(int seconds) {
    if (seconds <= 0) throw new IllegalArgumentException("seconds must be positive, was " + seconds);
  }
}
