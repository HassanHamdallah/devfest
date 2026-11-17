package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The phase rule: the first phase1Minutes of the final timer's active seconds are DECRYPTION, the rest INVESTIGATION. */
class FinalPhaseTest {

  private static final Instant START = Instant.parse("2026-11-20T18:00:00Z");

  @Test
  void theBoundaryBelongsToTheInvestigationPhase() {
    assertEquals(FinalPhase.DECRYPTION, FinalPhase.at(0, 15));
    assertEquals(FinalPhase.DECRYPTION, FinalPhase.at(899, 15));
    assertEquals(FinalPhase.INVESTIGATION, FinalPhase.at(900, 15));
    assertEquals(FinalPhase.INVESTIGATION, FinalPhase.at(2400, 15));
  }

  @Test
  void theSecondsLeftCountDownToTheBoundaryAndThenAreNull() {
    assertEquals(900, FinalPhase.phase1EndsInSeconds(0, 15));
    assertEquals(1, FinalPhase.phase1EndsInSeconds(899, 15));
    assertNull(FinalPhase.phase1EndsInSeconds(900, 15));
    assertNull(FinalPhase.phase1EndsInSeconds(1500, 15));
  }

  @Test
  void theLengthComesFromTheContent() {
    assertEquals(FinalPhase.DECRYPTION, FinalPhase.at(59, 1));
    assertEquals(FinalPhase.INVESTIGATION, FinalPhase.at(60, 1));
    assertEquals(FinalPhase.DECRYPTION, FinalPhase.at(39 * 60 - 1, 39));
  }

  @Test
  void pausedTimeIsNotActiveTimeAndAnyTeamSeesTheSameClock() {
    RoundTimer running = RoundTimer.assigned(2400).start(START);
    RoundTimer paused = running.pause(START.plus(Duration.ofMinutes(10)));
    Instant muchLater = START.plus(Duration.ofMinutes(50));
    assertEquals(600, paused.usedSeconds(muchLater));
    assertEquals(FinalPhase.DECRYPTION, FinalPhase.at(paused.usedSeconds(muchLater), 15));
    assertEquals(300, FinalPhase.phase1EndsInSeconds(paused.usedSeconds(muchLater), 15));
    // After the resume the clock goes on from 10:00 of active time, whenever a team entered the case.
    RoundTimer resumed = paused.resume(muchLater);
    int after3 = resumed.usedSeconds(muchLater.plus(Duration.ofMinutes(3)));
    assertEquals(FinalPhase.DECRYPTION, FinalPhase.at(after3, 15));
    int after5 = resumed.usedSeconds(muchLater.plus(Duration.ofMinutes(5)));
    assertEquals(FinalPhase.INVESTIGATION, FinalPhase.at(after5, 15));
  }
}
