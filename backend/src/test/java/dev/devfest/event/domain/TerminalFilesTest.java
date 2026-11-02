package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class TerminalFilesTest {

  @Test
  void acceptsRelativePathsWithTheAllowedCharacters() {
    for (String path : new String[] { "lock.log", "logs/lock.log", "logs/a-b_c.2024.txt", ".hidden", "a".repeat(120) }) {
      assertTrue(TerminalFiles.pathProblem(path).isEmpty(), path);
    }
  }

  @Test
  void rejectsTraversalAbsoluteEmptyAndForeignCharacters() {
    for (String path : new String[] { null, "", "/x", "../x", "a/../x", "a/..", "a//b", "a/", "./a", "a b", "a\\b", "ü", "a".repeat(121) }) {
      assertTrue(TerminalFiles.pathProblem(path).isPresent(), String.valueOf(path));
    }
  }

  @Test
  void deliversOnlyToTheAssignedTeamWhileTheRoundIsNotOver() {
    // Group C is in the Linux room in round 1.
    Optional<RoomCode> none = Optional.empty();
    for (RoundPhase phase : new RoundPhase[] { RoundPhase.ASSIGNED, RoundPhase.RUNNING, RoundPhase.PAUSED }) {
      assertTrue(TerminalFiles.deliverableTo(RoomCode.LINUX, true, GroupCode.C, false, 1, phase, none), phase.name());
    }
    assertFalse(TerminalFiles.deliverableTo(RoomCode.LINUX, true, GroupCode.C, false, 1, RoundPhase.TIME_UP, none));
    assertFalse(TerminalFiles.deliverableTo(RoomCode.LINUX, true, GroupCode.A, false, 1, RoundPhase.RUNNING, none));
    assertFalse(TerminalFiles.deliverableTo(RoomCode.LINUX, false, GroupCode.C, false, 1, RoundPhase.RUNNING, none));
    assertFalse(TerminalFiles.deliverableTo(RoomCode.LINUX, true, GroupCode.C, true, 1, RoundPhase.RUNNING, none));
    assertFalse(TerminalFiles.deliverableTo(RoomCode.LINUX, true, GroupCode.C, false, 0, null, none));
    assertFalse(TerminalFiles.deliverableTo(RoomCode.LINUX, true, GroupCode.C, false, 5, null, none));
    assertTrue(TerminalFiles.deliverableTo(RoomCode.LINUX, true, GroupCode.A, false, 1, RoundPhase.RUNNING, Optional.of(RoomCode.LINUX)));
    assertFalse(TerminalFiles.deliverableTo(RoomCode.LINUX, true, GroupCode.C, false, 1, RoundPhase.RUNNING, Optional.of(RoomCode.AI)));
  }
}
