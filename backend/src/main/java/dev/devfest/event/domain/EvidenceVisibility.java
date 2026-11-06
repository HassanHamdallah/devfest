package dev.devfest.event.domain;

import java.util.Map;
import java.util.Optional;

/**
 * When a team may look back at a room (its "evidence") and when it may see the room's official clue.
 *
 * <p>A room is finished for a team once a round in which the team was assigned to it (admin room exception for
 * that round, else the rotation) is over: a later round is current, or it is that round and the timer shows
 * TIME_UP. A room whose activity recorded the team's result (the AI quiz evaluation) is finished for that team at
 * once. Every room is finished once rooms are finished (round 5) or the final has ever opened. The clue value is
 * visible only for a finished room whose clue the team has collected.
 */
public final class EvidenceVisibility {

  private EvidenceVisibility() {}

  /**
   * @param exceptions round → room of every admin room exception the team had
   * @param phase the current round's effective phase (null in rounds 0 and 5)
   * @param resultRecorded the room's activity evaluated this team
   */
  public static boolean roomFinished(
    RoomCode room,
    GroupCode group,
    Map<Integer, RoomCode> exceptions,
    int currentRound,
    RoundPhase phase,
    boolean finalEverOpened,
    boolean resultRecorded
  ) {
    if (resultRecorded || finalEverOpened || currentRound >= Rotation.ROOMS_FINISHED) return true;
    for (int round = Rotation.FIRST_ROUND; round <= Rotation.LAST_ROUND; round++) {
      boolean over = round < currentRound || (round == currentRound && phase == RoundPhase.TIME_UP);
      if (over && RoomAssignment.isIn(room, group, round, Optional.ofNullable(exceptions.get(round)))) return true;
    }
    return false;
  }

  /** The official clue value may be sent only for a finished room whose clue this team collected. */
  public static boolean clueVisible(boolean roomFinished, boolean clueCollected) {
    return roomFinished && clueCollected;
  }
}
