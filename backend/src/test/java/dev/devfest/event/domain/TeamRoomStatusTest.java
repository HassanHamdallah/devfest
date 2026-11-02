package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TeamRoomStatusTest {

  @Test
  void managerVerdictWinsOverSubmissionActivity() {
    assertEquals(TeamRoomStatus.PASSED, TeamRoomStatus.of(JudgeStatus.PASS, true));
    assertEquals(TeamRoomStatus.FAILED, TeamRoomStatus.of(JudgeStatus.FAIL, true));
    assertEquals(TeamRoomStatus.PASSED, TeamRoomStatus.of(JudgeStatus.PASS, false));
  }

  @Test
  void pendingDependsOnWhetherAnythingWasSubmitted() {
    assertEquals(TeamRoomStatus.SUBMITTED, TeamRoomStatus.of(JudgeStatus.PENDING, true));
    assertEquals(TeamRoomStatus.NOT_STARTED, TeamRoomStatus.of(JudgeStatus.PENDING, false));
  }
}
