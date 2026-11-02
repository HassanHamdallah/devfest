package dev.devfest.event.domain;

/** A room manager's verdict on one clue answer; AUTOMATIC (stored as NULL) follows the room's clue key. */
public enum ClueOverride {
  ACCEPT,
  REJECT,
  AUTOMATIC;

  /** The submission_clue_verdict.override value: null for AUTOMATIC. */
  public String stored() {
    return this == AUTOMATIC ? null : name();
  }

  public static ClueOverride fromStored(String value) {
    return value == null ? AUTOMATIC : valueOf(value);
  }
}
