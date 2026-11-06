package dev.devfest.event.domain;

/**
 * The AI room's multiple-choice sections in the order teams answer them. {@code expectedCount} is the planned
 * number of questions (a warning, never a rule); {@code paceSeconds} is the pacing hint shown to teams.
 */
public enum QuizSection {
  EASY("Easy", 20, 12),
  MEDIUM("Medium", 15, 18),
  ADVANCED("Advanced", 6, 30),
  LANDMARK("Landmark", 3, 25),
  RECENT("Recent", 1, 25);

  private final String label;
  private final int expectedCount;
  private final int paceSeconds;

  QuizSection(String label, int expectedCount, int paceSeconds) {
    this.label = label;
    this.expectedCount = expectedCount;
    this.paceSeconds = paceSeconds;
  }

  public String label() {
    return label;
  }

  public int expectedCount() {
    return expectedCount;
  }

  public int paceSeconds() {
    return paceSeconds;
  }
}
