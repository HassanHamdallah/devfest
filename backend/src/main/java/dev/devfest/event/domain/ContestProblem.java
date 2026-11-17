package dev.devfest.event.domain;

/** The six problems A to F of the Codeforces contest (rules §20.4). Every solved problem is worth the same points. */
public enum ContestProblem {
  A,
  B,
  C,
  D,
  E,
  F;

  /** Points of one solved problem: 2 for each of the six, 12 in all. */
  public static final int POINTS = 2;

  public int points() {
    return POINTS;
  }
}
