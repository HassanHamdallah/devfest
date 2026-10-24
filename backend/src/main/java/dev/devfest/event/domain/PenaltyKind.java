package dev.devfest.event.domain;

/**
 * Penalty kinds with their fixed minute cost; only manager-entered kinds may be added by hand. TIME_ADJUSTMENT
 * is the admin's signed compensation (its minutes come with each row): it counts in adjusted time but is not a
 * penalty entry for the ranking's "fewer penalties" tie-break.
 */
public enum PenaltyKind {
  HINT(2, true, true),
  MINOR_VIOLATION(2, true, true),
  PROHIBITED_TOOL(5, true, true),
  CHEATING(5, true, true),
  ANSWER_SHARING(5, true, true),
  RECOVERY(5, false, true),
  WRONG_ACCUSATION(3, false, true),
  TIME_ADJUSTMENT(0, false, false);

  public static final int MAX_TIME_ADJUSTMENT_MINUTES = 60;

  private final int minutes;
  private final boolean managerEntered;
  private final boolean penaltyEntry;

  PenaltyKind(int minutes, boolean managerEntered, boolean penaltyEntry) {
    this.minutes = minutes;
    this.managerEntered = managerEntered;
    this.penaltyEntry = penaltyEntry;
  }

  /** The fixed cost; TIME_ADJUSTMENT has none (0) because each row carries its own signed minutes. */
  public int minutes() {
    return minutes;
  }

  /** Whether an active row of this kind counts in the ranking's penalty-entry tie-break. */
  public boolean penaltyEntry() {
    return penaltyEntry;
  }

  public boolean managerEntered() {
    return managerEntered;
  }
}
