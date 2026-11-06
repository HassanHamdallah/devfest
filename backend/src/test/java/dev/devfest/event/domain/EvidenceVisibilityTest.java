package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Group A visits AI in round 1, Problem solving in round 2, Linux in 3 and Hardware in 4. */
class EvidenceVisibilityTest {

  private static final Map<Integer, RoomCode> NO_EXCEPTIONS = Map.of();

  @Test
  void aRoomIsNotFinishedWhileItsRoundRuns() {
    for (RoundPhase phase : new RoundPhase[] { RoundPhase.ASSIGNED, RoundPhase.RUNNING, RoundPhase.PAUSED }) {
      assertFalse(finished(RoomCode.AI, NO_EXCEPTIONS, 1, phase), phase.name());
    }
    assertFalse(finished(RoomCode.AI, NO_EXCEPTIONS, 0, null), "not started");
    assertFalse(finished(RoomCode.PROBLEM_SOLVING, NO_EXCEPTIONS, 1, RoundPhase.TIME_UP), "round 2 is still ahead");
    assertFalse(EvidenceVisibility.clueVisible(false, true), "no key before the room is finished");
  }

  @Test
  void timeUpInTheTeamsRoundOrALaterRoundFinishesTheRoom() {
    assertTrue(finished(RoomCode.AI, NO_EXCEPTIONS, 1, RoundPhase.TIME_UP));
    assertTrue(finished(RoomCode.AI, NO_EXCEPTIONS, 2, RoundPhase.ASSIGNED));
    assertTrue(finished(RoomCode.LINUX, NO_EXCEPTIONS, 4, RoundPhase.RUNNING));
  }

  @Test
  void roundFiveAndTheFinalFinishEveryRoom() {
    for (RoomCode room : RoomCode.values()) {
      assertTrue(finished(room, NO_EXCEPTIONS, 5, null), room.name());
      assertTrue(EvidenceVisibility.roomFinished(room, GroupCode.A, NO_EXCEPTIONS, 2, RoundPhase.RUNNING, true, false));
    }
  }

  @Test
  void anAdminRoomExceptionIsTheAssignmentForItsRound() {
    Map<Integer, RoomCode> linuxInRoundOne = Map.of(1, RoomCode.LINUX);
    assertTrue(finished(RoomCode.LINUX, linuxInRoundOne, 1, RoundPhase.TIME_UP), "visited Linux instead of AI");
    assertFalse(finished(RoomCode.AI, linuxInRoundOne, 1, RoundPhase.TIME_UP), "never was in AI in round 1");
    assertFalse(finished(RoomCode.AI, linuxInRoundOne, 3, RoundPhase.RUNNING), "AI was replaced, not visited");
  }

  @Test
  void theKeyNeedsAFinishedRoomAndACollectedClue() {
    assertTrue(EvidenceVisibility.clueVisible(true, true));
    assertFalse(EvidenceVisibility.clueVisible(true, false), "finished but not collected");
    assertFalse(EvidenceVisibility.clueVisible(false, false));
    boolean finalOpen = EvidenceVisibility.roomFinished(RoomCode.HARDWARE, GroupCode.A, NO_EXCEPTIONS, 5, null, true, false);
    assertTrue(EvidenceVisibility.clueVisible(finalOpen, true), "final open + collected");
  }

  @Test
  void aRecordedActivityResultFinishesTheRoomAtOnce() {
    assertTrue(EvidenceVisibility.roomFinished(RoomCode.AI, GroupCode.A, NO_EXCEPTIONS, 1, RoundPhase.RUNNING, false, true));
    assertTrue(EvidenceVisibility.clueVisible(true, true), "passed: the clue shows while the round still runs");
  }

  private static boolean finished(RoomCode room, Map<Integer, RoomCode> exceptions, int round, RoundPhase phase) {
    return EvidenceVisibility.roomFinished(room, GroupCode.A, exceptions, round, phase, false, false);
  }
}
