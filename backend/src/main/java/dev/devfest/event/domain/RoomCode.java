package dev.devfest.event.domain;

/** The four fixed rooms; {@code position} is the rotation slot and matches event_room.position. */
public enum RoomCode {
  AI(0, "AI"),
  PROBLEM_SOLVING(1, "Problem solving"),
  LINUX(2, "Linux"),
  HARDWARE(3, "Hardware");

  private final int position;
  private final String displayName;

  RoomCode(int position, String displayName) {
    this.position = position;
    this.displayName = displayName;
  }

  public int position() {
    return position;
  }

  public String displayName() {
    return displayName;
  }

  public static RoomCode atPosition(int position) {
    for (RoomCode code : values()) {
      if (code.position == position) return code;
    }
    throw new IllegalArgumentException("No room at position " + position);
  }
}
