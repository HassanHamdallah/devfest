package dev.devfest.event.service;

import dev.devfest.event.repository.AdminRoomStatsRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * What the final still needs before it can open: the validated content file (imported by the admin) and a clue key for
 * every room. The admin board's alerts and the open-final check both use this, so an organizer never sees "nothing
 * missing" on the board while opening the final is refused.
 */
final class EventContentGaps {

  /** {@code message} is the board alert; {@code missingItem} is the entry in the open-final refusal. */
  record Gap(String code, String message, String missingItem) {}

  private EventContentGaps() {}

  static List<Gap> find(boolean hasFinalContent, List<AdminRoomStatsRecord> rooms) {
    List<Gap> gaps = new ArrayList<>();
    if (!hasFinalContent) {
      gaps.add(new Gap("MISSING_FINAL_CONTENT", "The final content file has not been imported.", "final content file"));
    }
    for (AdminRoomStatsRecord room : rooms) {
      if (!room.hasClueKey()) {
        gaps.add(new Gap("MISSING_CLUE_KEY", "Room " + room.name() + " has no clue key.", room.name() + " clue key"));
      }
    }
    return gaps;
  }
}
