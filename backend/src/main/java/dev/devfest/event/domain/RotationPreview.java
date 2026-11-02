package dev.devfest.event.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What moving a team to another group means for its rooms: the rooms it already visited, the rooms the target
 * group visits in the remaining rounds, and warnings for rooms it will miss or visit twice. Warnings never block.
 *
 * @param visited rooms already visited, in room position order
 * @param remaining the rooms of the remaining rounds after the move, by round
 * @param warnings MISS (a room never visited) and REPEAT (a room visited twice), in room position order
 */
public record RotationPreview(List<RoomCode> visited, List<Visit> remaining, List<Warning> warnings) {

  public record Visit(int round, RoomCode room) {}

  public enum WarningKind {
    MISS,
    REPEAT,
  }

  public record Warning(WarningKind kind, RoomCode room) {}

  /**
   * The first round whose room is still open to change: 1 before the event, the current round while it is
   * ASSIGNED (teams have not started), otherwise the next round; 5 (none) once the rooms are finished.
   */
  public static int firstRemainingRound(int currentRound, Optional<RoundPhase> phase) {
    if (currentRound < Rotation.FIRST_ROUND) return Rotation.FIRST_ROUND;
    if (currentRound > Rotation.LAST_ROUND) return Rotation.ROOMS_FINISHED;
    return phase.filter(p -> p == RoundPhase.ASSIGNED).isPresent() ? currentRound : currentRound + 1;
  }

  /**
   * Rooms of the rounds before {@code firstRemainingRound} (rotation of {@code group}, or the exception of that
   * round) plus every room the team submitted to.
   */
  public static Set<RoomCode> visitedRooms(
    GroupCode group,
    int firstRemainingRound,
    Map<Integer, RoomCode> exceptionsByRound,
    Set<RoomCode> submittedRooms
  ) {
    Set<RoomCode> visited = EnumSet.noneOf(RoomCode.class);
    visited.addAll(submittedRooms);
    for (int round = Rotation.FIRST_ROUND; round < firstRemainingRound && round <= Rotation.LAST_ROUND; round++) {
      RoomAssignment.roomFor(group, round, Optional.ofNullable(exceptionsByRound.get(round))).ifPresent(visited::add);
    }
    return visited;
  }

  public static RotationPreview of(Set<RoomCode> visited, GroupCode targetGroup, int firstRemainingRound) {
    List<Visit> remaining = new ArrayList<>();
    for (int round = Math.max(Rotation.FIRST_ROUND, firstRemainingRound); round <= Rotation.LAST_ROUND; round++) {
      remaining.add(new Visit(round, Rotation.roomFor(targetGroup, round).orElseThrow()));
    }
    Map<RoomCode, Integer> visits = new EnumMap<>(RoomCode.class);
    visited.forEach(room -> visits.merge(room, 1, Integer::sum));
    remaining.forEach(visit -> visits.merge(visit.room(), 1, Integer::sum));
    List<Warning> warnings = new ArrayList<>();
    List<RoomCode> visitedInOrder = new ArrayList<>();
    for (RoomCode room : RoomCode.values()) {
      int count = visits.getOrDefault(room, 0);
      if (count == 0) warnings.add(new Warning(WarningKind.MISS, room));
      if (count > 1) warnings.add(new Warning(WarningKind.REPEAT, room));
      if (visited.contains(room)) visitedInOrder.add(room);
    }
    return new RotationPreview(List.copyOf(visitedInOrder), List.copyOf(remaining), List.copyOf(warnings));
  }
}
