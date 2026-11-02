package dev.devfest.event.domain;

import java.util.Optional;

/**
 * The one place that decides which room a team goes to in a round: the admin's room exception for that
 * (round, team) when there is one, otherwise the fixed rotation. Rounds 0 and 5 schedule no room.
 */
public final class RoomAssignment {

  private RoomAssignment() {}

  public static Optional<RoomCode> roomFor(GroupCode group, int round, Optional<RoomCode> exception) {
    if (!Rotation.isRunningRound(round)) return Optional.empty();
    return exception.isPresent() ? exception : Rotation.roomFor(group, round);
  }

  /** True when the team (group + exception) is scheduled into {@code room} in {@code round}. */
  public static boolean isIn(RoomCode room, GroupCode group, int round, Optional<RoomCode> exception) {
    return roomFor(group, round, exception).filter(room::equals).isPresent();
  }
}
