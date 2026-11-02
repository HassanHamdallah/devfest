package dev.devfest.event.service;

import static dev.devfest.event.repository.SuspectRepository.MAX_SUSPECTS;

import dev.devfest.event.repository.AdminRoomStatsRecord;
import dev.devfest.event.repository.ContentStatusRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * What the final still needs before it can open. The admin board's alerts and the open-final check both
 * use this, so an organizer never sees "nothing missing" on the board while opening the final is refused.
 */
final class EventContentGaps {

  /** {@code message} is the board alert; {@code missingItem} is the entry in the open-final refusal. */
  record Gap(String code, String message, String missingItem) {}

  private EventContentGaps() {}

  static List<Gap> find(ContentStatusRecord content, List<AdminRoomStatsRecord> rooms) {
    List<Gap> gaps = new ArrayList<>();
    if (!content.hasEncryptedMessage()) {
      gaps.add(new Gap("MISSING_MESSAGE", "The encrypted message is empty.", "encrypted message"));
    }
    if (content.suspectCount() < MAX_SUSPECTS) {
      String message = "%d suspects are required (%d defined).".formatted(MAX_SUSPECTS, content.suspectCount());
      gaps.add(new Gap("MISSING_SUSPECTS", message, MAX_SUSPECTS + " suspects"));
    }
    if (!content.hasCorrectSuspect()) {
      gaps.add(new Gap("MISSING_CORRECT_SUSPECT", "No correct suspect is selected.", "correct suspect"));
    }
    for (AdminRoomStatsRecord room : rooms) {
      if (!room.hasClueKey()) {
        gaps.add(new Gap("MISSING_CLUE_KEY", "Room " + room.name() + " has no clue key.", room.name() + " clue key"));
      }
      if (!room.hasRecoveryChallenge()) {
        String message = "Room " + room.name() + " has no recovery challenge.";
        gaps.add(new Gap("MISSING_RECOVERY", message, room.name() + " recovery challenge"));
      }
    }
    return gaps;
  }
}
