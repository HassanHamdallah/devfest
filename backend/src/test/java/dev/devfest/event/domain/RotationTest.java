package dev.devfest.event.domain;

import static dev.devfest.event.domain.RoomCode.AI;
import static dev.devfest.event.domain.RoomCode.HARDWARE;
import static dev.devfest.event.domain.RoomCode.LINUX;
import static dev.devfest.event.domain.RoomCode.PROBLEM_SOLVING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class RotationTest {

  /** The contract's rotation table: group → rooms for rounds 1..4. */
  private static final Map<GroupCode, RoomCode[]> TABLE = Map.of(
    GroupCode.A, new RoomCode[] { AI, PROBLEM_SOLVING, LINUX, HARDWARE },
    GroupCode.B, new RoomCode[] { PROBLEM_SOLVING, LINUX, HARDWARE, AI },
    GroupCode.C, new RoomCode[] { LINUX, HARDWARE, AI, PROBLEM_SOLVING },
    GroupCode.D, new RoomCode[] { HARDWARE, AI, PROBLEM_SOLVING, LINUX }
  );

  @Test
  void everyGroupAndRoundMatchesTheContractTable() {
    for (GroupCode group : GroupCode.values()) {
      for (int round = 1; round <= 4; round++) {
        RoomCode expected = TABLE.get(group)[round - 1];
        String pair = group + " round " + round;
        assertEquals(OptionalInt.of(expected.position()), Rotation.roomPositionFor(group.index(), round), pair);
        assertEquals(Optional.of(expected), Rotation.roomFor(group, round), pair);
      }
    }
  }

  @Test
  void roundsZeroAndFiveScheduleNoRoom() {
    for (GroupCode group : GroupCode.values()) {
      assertTrue(Rotation.roomPositionFor(group.index(), 0).isEmpty());
      assertTrue(Rotation.roomPositionFor(group.index(), 5).isEmpty());
      assertTrue(Rotation.roomFor(group, 0).isEmpty());
    }
    assertTrue(Rotation.groupIn(0, 0).isEmpty());
    assertTrue(Rotation.groupIn(0, 5).isEmpty());
  }

  @Test
  void roundForIsTheInverseOfRoomPositionFor() {
    for (int group = 0; group < 4; group++) {
      for (int round = 1; round <= 4; round++) {
        int position = Rotation.roomPositionFor(group, round).getAsInt();
        assertEquals(round, Rotation.roundFor(group, position));
      }
    }
  }

  @Test
  void eachRoomHostsExactlyTheScheduledGroup() {
    for (GroupCode group : GroupCode.values()) {
      for (int round = 1; round <= 4; round++) {
        int position = TABLE.get(group)[round - 1].position();
        assertEquals(Optional.of(group), Rotation.groupIn(position, round));
      }
    }
  }

  @Test
  void rejectsOutOfRangeInputs() {
    assertThrows(IllegalArgumentException.class, () -> Rotation.roomPositionFor(4, 1));
    assertThrows(IllegalArgumentException.class, () -> Rotation.roomPositionFor(-1, 1));
    assertThrows(IllegalArgumentException.class, () -> Rotation.roomPositionFor(0, 6));
    assertThrows(IllegalArgumentException.class, () -> Rotation.roomPositionFor(0, -1));
    assertThrows(IllegalArgumentException.class, () -> Rotation.roundFor(0, 4));
    assertThrows(IllegalArgumentException.class, () -> Rotation.roundFor(4, 0));
    assertThrows(IllegalArgumentException.class, () -> Rotation.groupIn(4, 1));
  }
}
