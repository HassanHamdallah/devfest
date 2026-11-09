package dev.devfest.event.domain;

/** Derives a team's final status from stored facts; the qualification matrix lives here. */
public final class FinalStageResolver {

  public static final int REQUIRED_CLUES = 4;

  private FinalStageResolver() {}

  /**
   * @param finalEverOpened the admin has opened the final
   * @param collectedClues 0..4
   * @param finalised the team's final is over (suspect submitted, time up, or the final closed)
   */
  public static FinalStage resolve(boolean finalEverOpened, int collectedClues, boolean finalised) {
    if (collectedClues < 0 || collectedClues > REQUIRED_CLUES) {
      throw new IllegalArgumentException("Collected clues must be 0..4, was " + collectedClues);
    }
    if (!finalEverOpened) return FinalStage.LOCKED;
    if (collectedClues <= REQUIRED_CLUES - 2) return FinalStage.NOT_QUALIFIED;
    if (finalised) return FinalStage.COMPLETED;
    return collectedClues == REQUIRED_CLUES ? FinalStage.IN_FINAL : FinalStage.RECOVERY;
  }
}
