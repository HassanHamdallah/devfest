package dev.devfest.event.domain;

import java.time.Instant;

/**
 * The AI room's two parts inside the one round timer (rules §19.3). "Elapsed" is the round's running time only
 * (pauses do not count; added time changes the end of the round, not the elapsed time).
 *
 * <ul>
 *   <li>MCQ: the timer has started, elapsed &lt; the room's MCQ minutes, and the team did not finish its MCQs.</li>
 *   <li>REPAIRS: the timer has started and the MCQ part is over (cutoff reached or "Finish MCQs").</li>
 *   <li>TIME_UP: the round's timer shows Time's up; nothing more is accepted.</li>
 * </ul>
 * While PAUSED the stage is unchanged but no answer is accepted (as for every room submission).
 */
public final class QuizWindow {

  public enum Stage {
    NOT_STARTED,
    MCQ,
    REPAIRS,
    TIME_UP,
  }

  private QuizWindow() {}

  public static Stage stage(RoundTimer timer, Instant now, int mcqSeconds, boolean mcqFinished) {
    RoundPhase phase = timer.effectivePhase(now);
    if (phase == RoundPhase.ASSIGNED) return Stage.NOT_STARTED;
    if (phase == RoundPhase.TIME_UP) return Stage.TIME_UP;
    return !mcqFinished && elapsedSeconds(timer, now) < mcqSeconds ? Stage.MCQ : Stage.REPAIRS;
  }

  /** Running time of the round so far, in whole seconds (0 before the timer starts). */
  public static int elapsedSeconds(RoundTimer timer, Instant now) {
    return timer.phase() == RoundPhase.ASSIGNED ? 0 : timer.usedSeconds(now);
  }

  /** MCQ time left: the MCQ cutoff minus elapsed, never more than the round has left and never negative. */
  public static int mcqRemainingSeconds(RoundTimer timer, Instant now, int mcqSeconds) {
    int untilCutoff = Math.max(0, mcqSeconds - elapsedSeconds(timer, now));
    return Math.min(untilCutoff, timer.remainingSeconds(now));
  }
}
