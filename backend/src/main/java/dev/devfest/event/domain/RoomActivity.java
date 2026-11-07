package dev.devfest.event.domain;

/**
 * A room's team activity (admin setting, one per room). A room without one (null) keeps the plain answer form; a
 * room with one shows the activity instead, and the server records the team's result itself (a plain submission to
 * such a room is refused). {@link #CONTEST_RESULTS}: the Codeforces contest of the Problem Solving room, whose results the
 * organizer types in and whose clue the organizer releases (rules §20). {@link #AI_QUIZ}: auto-scored questions and repairs (rules §19). {@link #LINUX_TERMINAL}:
 * the room's terminal with its five-field form, whose final answer is recorded as the room's submission (rules §18).
 */
public enum RoomActivity {
  AI_QUIZ,
  LINUX_TERMINAL,
  CONTEST_RESULTS;

  /** The stored column value; null means no activity. */
  public static RoomActivity fromStored(String value) {
    return value == null ? null : valueOf(value);
  }
}
