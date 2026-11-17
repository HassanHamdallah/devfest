package dev.devfest.event.domain;

/**
 * A team's standing in the Codeforces standings of its round, picked by the organizer (rules §20.4). Ties are allowed:
 * two teams may both be FIRST. The bonus is 3, 2 and 1 for the first three standings and 0 for any other.
 */
public enum ContestStanding {
  FIRST("1st", 3),
  SECOND("2nd", 2),
  THIRD("3rd", 1),
  OTHER("Other", 0);

  private final String label;
  private final int bonus;

  ContestStanding(String label, int bonus) {
    this.label = label;
    this.bonus = bonus;
  }

  /** The text people read: 1st, 2nd, 3rd or Other. */
  public String label() {
    return label;
  }

  public int bonus() {
    return bonus;
  }
}
