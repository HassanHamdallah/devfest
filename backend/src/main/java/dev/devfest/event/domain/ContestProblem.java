package dev.devfest.event.domain;

/** The five problems A to E of the Codeforces contest and their points (rules §20.4): 2 + 3 + 3 + 2 + 2 = 12. */
public enum ContestProblem {
  A(2),
  B(3),
  C(3),
  D(2),
  E(2);

  private final int points;

  ContestProblem(int points) {
    this.points = points;
  }

  public int points() {
    return points;
  }
}
