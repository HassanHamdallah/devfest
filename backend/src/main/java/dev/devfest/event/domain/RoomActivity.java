package dev.devfest.event.domain;

/**
 * A room's team activity, shown on the team page instead of the technical-output field (rules §19). A room
 * without one (null) keeps the plain answer form.
 */
public enum RoomActivity {
  AI_QUIZ;

  /** The stored column value; null means no activity. */
  public static RoomActivity fromStored(String value) {
    return value == null ? null : valueOf(value);
  }
}
