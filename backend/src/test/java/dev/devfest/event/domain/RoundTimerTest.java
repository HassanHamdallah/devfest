package dev.devfest.event.domain;

import static dev.devfest.event.domain.RoundPhase.ASSIGNED;
import static dev.devfest.event.domain.RoundPhase.PAUSED;
import static dev.devfest.event.domain.RoundPhase.RUNNING;
import static dev.devfest.event.domain.RoundPhase.TIME_UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RoundTimerTest {

  private static final Instant T0 = Instant.parse("2026-10-24T09:00:00Z");
  private static final int TEN_MINUTES = 600;

  private static Instant at(long seconds) {
    return T0.plusSeconds(seconds);
  }

  private static RoundTimer started() {
    return RoundTimer.assigned(TEN_MINUTES).start(T0);
  }

  @Test
  void anAssignedRoundShowsItsFullDurationAndDoesNotCountDown() {
    RoundTimer timer = RoundTimer.assigned(TEN_MINUTES);
    assertEquals(ASSIGNED, timer.effectivePhase(at(5000)));
    assertEquals(TEN_MINUTES, timer.remainingSeconds(at(5000)));
  }

  @Test
  void aRunningTimerCountsDownInWholeSecondsAndStopsAtZero() {
    RoundTimer timer = started();
    assertEquals(RUNNING, timer.effectivePhase(T0));
    assertEquals(600, timer.remainingSeconds(T0));
    assertEquals(599, timer.remainingSeconds(T0.plusMillis(1999)));
    assertEquals(1, timer.remainingSeconds(at(599)));
    assertEquals(0, timer.remainingSeconds(at(600)));
    assertEquals(TIME_UP, timer.effectivePhase(at(600)));
    assertEquals(0, timer.remainingSeconds(at(99_999)), "never negative");
    assertEquals(600, timer.remainingSeconds(T0.minusSeconds(30)), "a clock behind resumedAt counts nothing");
  }

  @Test
  void severalPausesFreezeTheRemainingTime() {
    RoundTimer paused = started().pause(at(100));
    assertEquals(PAUSED, paused.effectivePhase(at(100)));
    assertEquals(500, paused.remainingSeconds(at(100)));
    assertEquals(500, paused.remainingSeconds(at(900)), "frozen while paused");

    RoundTimer resumed = paused.resume(at(900));
    assertEquals(500, resumed.remainingSeconds(at(900)));
    assertEquals(450, resumed.remainingSeconds(at(950)));

    RoundTimer pausedAgain = resumed.pause(at(1000));
    assertEquals(400, pausedAgain.remainingSeconds(at(5000)));
    assertEquals(200, pausedAgain.elapsedBeforeSeconds());
    RoundTimer last = pausedAgain.resume(at(6000));
    assertEquals(0, last.remainingSeconds(at(6400)));
    assertEquals(TIME_UP, last.effectivePhase(at(6400)));
  }

  @Test
  void addingTimeExtendsARunningOrPausedOrAssignedRound() {
    assertEquals(660, RoundTimer.assigned(TEN_MINUTES).addSeconds(60, at(10)).remainingSeconds(at(10)));
    assertEquals(400, started().addSeconds(300, at(500)).remainingSeconds(at(500)));
    RoundTimer paused = started().pause(at(100)).addSeconds(60, at(200));
    assertEquals(PAUSED, paused.effectivePhase(at(200)));
    assertEquals(560, paused.remainingSeconds(at(9999)));
  }

  @Test
  void addingTimeAfterTimeIsUpRunsAgainWithExactlyTheAddedTime() {
    RoundTimer timeUp = started();
    assertEquals(TIME_UP, timeUp.effectivePhase(at(700)));
    RoundTimer more = timeUp.addSeconds(300, at(700));
    assertEquals(RUNNING, more.effectivePhase(at(700)));
    assertEquals(300, more.remainingSeconds(at(700)));
    assertEquals(240, more.remainingSeconds(at(760)));
    assertEquals(900, more.durationSeconds());
  }

  @Test
  void addingTimeAfterEndingWhilePausedStaysPaused() {
    RoundTimer ended = started().pause(at(120)).endNow(at(130));
    assertEquals(TIME_UP, ended.effectivePhase(at(130)));
    RoundTimer more = ended.addSeconds(60, at(500));
    assertEquals(PAUSED, more.effectivePhase(at(500)));
    assertEquals(60, more.remainingSeconds(at(9999)));
  }

  @Test
  void removingTimeNeverGoesBelowTheTimeUsed() {
    assertEquals(240, started().removeSeconds(60, at(300)).remainingSeconds(at(300)));
    RoundTimer cut = started().removeSeconds(400, at(300));
    assertEquals(TIME_UP, cut.effectivePhase(at(300)));
    assertEquals(0, cut.remainingSeconds(at(300)));
    assertEquals(300, cut.elapsedBeforeSeconds(), "the time used so far is kept");
    assertEquals(60, cut.addSeconds(60, at(400)).remainingSeconds(at(400)));
    assertEquals(540, RoundTimer.assigned(TEN_MINUTES).removeSeconds(60, T0).remainingSeconds(T0));
    assertThrows(RoundTimer.IllegalTransition.class, () -> RoundTimer.assigned(60).removeSeconds(60, T0));
    RoundTimer timeUp = started();
    assertSame(timeUp, timeUp.removeSeconds(60, at(700)), "nothing left to remove");
  }

  @Test
  void endingNowLeavesNoTimeAndKeepsTheTimeUsed() {
    RoundTimer ended = started().endNow(at(250));
    assertEquals(TIME_UP, ended.effectivePhase(at(250)));
    assertEquals(0, ended.remainingSeconds(at(250)));
    assertEquals(250, ended.elapsedBeforeSeconds());
    assertSame(ended, ended.endNow(at(300)));
    RoundTimer resumed = ended.addSeconds(30, at(1000));
    assertEquals(30, resumed.remainingSeconds(at(1000)));
  }

  @Test
  void repeatingATransitionIntoTheCurrentStateChangesNothing() {
    RoundTimer running = started();
    assertSame(running, running.start(at(5)));
    assertSame(running, running.resume(at(5)));
    RoundTimer paused = running.pause(at(10));
    assertSame(paused, paused.pause(at(20)));
  }

  @Test
  void invalidTransitionsExplainWhy() {
    RoundTimer assigned = RoundTimer.assigned(TEN_MINUTES);
    assertMessage("The timer has not started yet.", () -> assigned.pause(T0));
    assertMessage("The timer has not started yet. Start it instead.", () -> assigned.resume(T0));
    assertMessage("The timer has not started yet.", () -> assigned.endNow(T0));
    RoundTimer paused = started().pause(at(10));
    assertMessage("The timer is paused. Resume it instead.", () -> paused.start(at(20)));
    RoundTimer timeUp = started();
    assertMessage("Time is up for this round. Add time to continue.", () -> timeUp.start(at(700)));
    assertMessage("Time is up for this round.", () -> timeUp.pause(at(700)));
    assertMessage("Time is up for this round. Add time to continue.", () -> timeUp.resume(at(700)));
    assertMessage("A round cannot be longer than 24 hours.", () -> RoundTimer.assigned(86_000).addSeconds(3600, T0));
    assertThrows(IllegalArgumentException.class, () -> started().addSeconds(0, T0));
    assertThrows(IllegalArgumentException.class, () -> new RoundTimer(TIME_UP, 60, 0, null, false));
    assertThrows(IllegalArgumentException.class, () -> new RoundTimer(RUNNING, 60, 0, null, false));
    assertThrows(IllegalArgumentException.class, () -> new RoundTimer(PAUSED, 60, -1, null, false));
  }

  private static void assertMessage(String message, Runnable transition) {
    assertEquals(message, assertThrows(RoundTimer.IllegalTransition.class, transition::run).getMessage());
  }
}
