package dev.devfest.event.domain;

import static dev.devfest.event.domain.QuizWindow.Stage.MCQ;
import static dev.devfest.event.domain.QuizWindow.Stage.NOT_STARTED;
import static dev.devfest.event.domain.QuizWindow.Stage.REPAIRS;
import static dev.devfest.event.domain.QuizWindow.Stage.TIME_UP;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class QuizWindowTest {

  private static final Instant T0 = Instant.parse("2026-11-02T09:00:00Z");
  private static final int ROUND = 25 * 60;
  private static final int CUTOFF = 14 * 60;

  private static Instant at(long seconds) {
    return T0.plusSeconds(seconds);
  }

  @Test
  void beforeTheTimerStartsNothingIsOpen() {
    RoundTimer assigned = RoundTimer.assigned(ROUND);
    assertEquals(NOT_STARTED, QuizWindow.stage(assigned, at(5000), CUTOFF, false));
    assertEquals(0, QuizWindow.elapsedSeconds(assigned, at(5000)));
  }

  @Test
  void theMcqPartEndsAtTheCutoffOfRunningTime() {
    RoundTimer timer = RoundTimer.assigned(ROUND).start(T0);
    assertEquals(MCQ, QuizWindow.stage(timer, at(CUTOFF - 1), CUTOFF, false));
    assertEquals(1, QuizWindow.mcqRemainingSeconds(timer, at(CUTOFF - 1), CUTOFF));
    assertEquals(REPAIRS, QuizWindow.stage(timer, at(CUTOFF), CUTOFF, false));
    assertEquals(0, QuizWindow.mcqRemainingSeconds(timer, at(CUTOFF + 60), CUTOFF));
    assertEquals(TIME_UP, QuizWindow.stage(timer, at(ROUND), CUTOFF, false));
  }

  @Test
  void finishMcqsOpensTheRepairsEarly() {
    RoundTimer timer = RoundTimer.assigned(ROUND).start(T0);
    assertEquals(REPAIRS, QuizWindow.stage(timer, at(60), CUTOFF, true));
  }

  @Test
  void pausesDoNotCountTowardsTheCutoff() {
    // 10 minutes running, paused for 30 minutes, resumed: 4 more running minutes are left for the MCQs.
    RoundTimer paused = RoundTimer.assigned(ROUND).start(T0).pause(at(600));
    assertEquals(MCQ, QuizWindow.stage(paused, at(600 + 1800), CUTOFF, false), "the stage stays while paused");
    assertEquals(240, QuizWindow.mcqRemainingSeconds(paused, at(600 + 1800), CUTOFF));
    RoundTimer resumed = paused.resume(at(2400));
    assertEquals(MCQ, QuizWindow.stage(resumed, at(2400 + 239), CUTOFF, false));
    assertEquals(REPAIRS, QuizWindow.stage(resumed, at(2400 + 240), CUTOFF, false));
  }

  @Test
  void addedTimeExtendsTheRoundButNotTheMcqPart() {
    RoundTimer timer = RoundTimer.assigned(ROUND).start(T0).addSeconds(600, at(300));
    assertEquals(MCQ, QuizWindow.stage(timer, at(CUTOFF - 1), CUTOFF, false));
    assertEquals(REPAIRS, QuizWindow.stage(timer, at(CUTOFF), CUTOFF, false));
    assertEquals(REPAIRS, QuizWindow.stage(timer, at(ROUND + 599), CUTOFF, false));
    assertEquals(TIME_UP, QuizWindow.stage(timer, at(ROUND + 600), CUTOFF, false));
  }

  @Test
  void aRoundShorterThanTheCutoffKeepsTheMcqsOpenUntilTimeIsUp() {
    RoundTimer timer = RoundTimer.assigned(600).start(T0);
    assertEquals(MCQ, QuizWindow.stage(timer, at(599), CUTOFF, false));
    assertEquals(1, QuizWindow.mcqRemainingSeconds(timer, at(599), CUTOFF), "never more than the round has left");
    assertEquals(TIME_UP, QuizWindow.stage(timer, at(600), CUTOFF, false));
  }

  @Test
  void timeAddedAfterTimeIsUpReopensTheRepairsNotTheMcqs() {
    RoundTimer ended = RoundTimer.assigned(ROUND).start(T0).endNow(at(CUTOFF + 120));
    assertEquals(TIME_UP, QuizWindow.stage(ended, at(CUTOFF + 200), CUTOFF, false));
    RoundTimer extended = ended.addSeconds(300, at(CUTOFF + 200));
    assertEquals(REPAIRS, QuizWindow.stage(extended, at(CUTOFF + 200), CUTOFF, false));

    RoundTimer endedEarly = RoundTimer.assigned(ROUND).start(T0).endNow(at(60));
    RoundTimer reopened = endedEarly.addSeconds(300, at(100));
    assertEquals(MCQ, QuizWindow.stage(reopened, at(100), CUTOFF, false), "only 60 s of running time were used");
  }
}
