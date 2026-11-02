package dev.devfest.event.service;

import dev.devfest.event.repository.QuizContentRepository.RoomStats;
import java.util.ArrayList;
import java.util.List;

/**
 * AI quiz rooms that cannot run a round (rules §19.1): no clue key (a passing team would get no clue) or no
 * questions. The admin board shows them as blocking alerts, and starting the round's timer is refused while any
 * exists unless the admin presses "Start anyway".
 */
final class AiRoomStartCheck {

  /** One blocking problem; {@code roomId} is the room whose quiz editor fixes it. */
  record Blocker(long roomId, String code, String message) {}

  private AiRoomStartCheck() {}

  static List<Blocker> blockers(List<RoomStats> quizRooms) {
    List<Blocker> blockers = new ArrayList<>();
    for (RoomStats room : quizRooms) {
      if (!room.hasClueKey()) {
        String message = "Room " + room.roomName() + " runs the AI quiz but has no clue key, so passing teams would get no clue.";
        blockers.add(new Blocker(room.roomId(), "QUIZ_WITHOUT_CLUE_KEY", message));
      }
      if (room.questions() == 0) {
        blockers.add(new Blocker(room.roomId(), "QUIZ_WITHOUT_QUESTIONS", "Room " + room.roomName() + " runs the AI quiz but has no questions."));
      }
    }
    return blockers;
  }
}
