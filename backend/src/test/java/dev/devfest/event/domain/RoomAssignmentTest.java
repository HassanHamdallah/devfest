package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class RoomAssignmentTest {

  @Test
  void withoutAnExceptionTheRotationDecides() {
    for (int round = 1; round <= 4; round++) {
      assertEquals(Rotation.roomFor(GroupCode.B, round), RoomAssignment.roomFor(GroupCode.B, round, Optional.empty()));
    }
  }

  @Test
  void anExceptionReplacesTheRotationRoomForThatRound() {
    assertEquals(Optional.of(RoomCode.LINUX), RoomAssignment.roomFor(GroupCode.A, 1, Optional.of(RoomCode.LINUX)));
    assertTrue(RoomAssignment.isIn(RoomCode.LINUX, GroupCode.A, 1, Optional.of(RoomCode.LINUX)));
    assertFalse(RoomAssignment.isIn(RoomCode.AI, GroupCode.A, 1, Optional.of(RoomCode.LINUX)), "not in its rotation room");
    assertTrue(RoomAssignment.isIn(RoomCode.AI, GroupCode.A, 1, Optional.empty()));
  }

  @Test
  void roundsWithoutRoomsIgnoreExceptions() {
    assertEquals(Optional.empty(), RoomAssignment.roomFor(GroupCode.A, 0, Optional.of(RoomCode.AI)));
    assertEquals(Optional.empty(), RoomAssignment.roomFor(GroupCode.A, 5, Optional.of(RoomCode.AI)));
  }
}
