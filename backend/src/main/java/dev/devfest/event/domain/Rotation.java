package dev.devfest.event.domain;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Fixed room rotation: in round r (1..4) group g visits room position (g + r - 1) mod 4.
 * Round 0 (not started) and round 5 (rooms finished) schedule no room.
 */
public final class Rotation {

  public static final int ROOMS = 4;
  public static final int FIRST_ROUND = 1;
  public static final int LAST_ROUND = 4;
  public static final int ROOMS_FINISHED = 5;

  private Rotation() {}

  public static boolean isRunningRound(int round) {
    return round >= FIRST_ROUND && round <= LAST_ROUND;
  }

  public static OptionalInt roomPositionFor(int groupIndex, int round) {
    requireIndex("Group index", groupIndex);
    requireRound(round);
    if (!isRunningRound(round)) return OptionalInt.empty();
    return OptionalInt.of((groupIndex + round - 1) % ROOMS);
  }

  /** The round (1..4) in which the group visits the room at {@code position}. */
  public static int roundFor(int groupIndex, int position) {
    requireIndex("Group index", groupIndex);
    requireIndex("Room position", position);
    return Math.floorMod(position - groupIndex, ROOMS) + 1;
  }

  public static Optional<RoomCode> roomFor(GroupCode group, int round) {
    OptionalInt position = roomPositionFor(group.index(), round);
    return position.isPresent() ? Optional.of(RoomCode.atPosition(position.getAsInt())) : Optional.empty();
  }

  /** The group scheduled into the room at {@code position} during {@code round}, if rooms are running. */
  public static Optional<GroupCode> groupIn(int position, int round) {
    requireIndex("Room position", position);
    requireRound(round);
    if (!isRunningRound(round)) return Optional.empty();
    return Optional.of(GroupCode.fromIndex(Math.floorMod(position - round + 1, ROOMS)));
  }

  private static void requireIndex(String label, int value) {
    if (value < 0 || value >= ROOMS) {
      throw new IllegalArgumentException(label + " must be 0..3, was " + value);
    }
  }

  private static void requireRound(int round) {
    if (round < 0 || round > ROOMS_FINISHED) {
      throw new IllegalArgumentException("Round must be 0..5, was " + round);
    }
  }
}
