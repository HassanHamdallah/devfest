package dev.devfest.event.domain;

/** Penalty kinds with their fixed minute cost; only manager-entered kinds may be added by hand. */
public enum PenaltyKind {
  HINT(2, true),
  MINOR_VIOLATION(2, true),
  PROHIBITED_TOOL(5, true),
  CHEATING(5, true),
  ANSWER_SHARING(5, true),
  RECOVERY(5, false),
  WRONG_ACCUSATION(3, false);

  private final int minutes;
  private final boolean managerEntered;

  PenaltyKind(int minutes, boolean managerEntered) {
    this.minutes = minutes;
    this.managerEntered = managerEntered;
  }

  public int minutes() {
    return minutes;
  }

  public boolean managerEntered() {
    return managerEntered;
  }
}
