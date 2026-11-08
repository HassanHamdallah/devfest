package dev.devfest.event.service;

import dev.devfest.event.repository.SentinelContentRepository.RoomStats;
import java.util.ArrayList;
import java.util.List;

/**
 * AI mission rooms that cannot run a round (rules §19.8): no clue key (a passing team would get no clue) or no
 * mission content. The admin board shows them as blocking alerts, and starting the round's timer is refused while
 * any exists unless the admin presses "Start anyway".
 */
final class AiRoomStartCheck {

  /** One blocking problem; {@code roomId} is the room whose mission page fixes it. */
  record Blocker(long roomId, String code, String message) {}

  private AiRoomStartCheck() {}

  static List<Blocker> blockers(List<RoomStats> missionRooms) {
    List<Blocker> blockers = new ArrayList<>();
    for (RoomStats room : missionRooms) {
      if (!room.hasClueKey()) {
        String message = "Room " + room.roomName() + " runs the AI mission but has no clue key, so passing teams would get no clue.";
        blockers.add(new Blocker(room.roomId(), "MISSION_WITHOUT_CLUE_KEY", message));
      }
      if (!room.hasContent()) {
        blockers.add(new Blocker(room.roomId(), "MISSION_WITHOUT_CONTENT", "Room " + room.roomName() + " runs the AI mission but has no mission content."));
      }
    }
    return blockers;
  }
}
