package dev.devfest.event.domain;

/** Derives a team's final stage; the checks run in contract order so FINISHED survives a closed final. */
public final class FinalStageResolver {

  public static final int REQUIRED_CLUES = 4;

  private FinalStageResolver() {}

  public static FinalStage resolve(
    boolean finalEverOpened,
    boolean finalOpen,
    boolean finished,
    int collectedClues
  ) {
    if (collectedClues < 0 || collectedClues > REQUIRED_CLUES) {
      throw new IllegalArgumentException("Collected clues must be 0..4, was " + collectedClues);
    }
    if (finished) return FinalStage.FINISHED;
    if (!finalEverOpened) return FinalStage.LOCKED;
    if (!finalOpen) return FinalStage.CLOSED;
    if (collectedClues == REQUIRED_CLUES) return FinalStage.MESSAGE;
    if (collectedClues == REQUIRED_CLUES - 1) return FinalStage.RECOVERY;
    return FinalStage.NOT_ENOUGH_CLUES;
  }
}
