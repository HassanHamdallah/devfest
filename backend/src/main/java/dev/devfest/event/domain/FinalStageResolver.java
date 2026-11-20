package dev.devfest.event.domain;

import dev.devfest.event.domain.recovery.RecoveryPath;

/** Derives a team's final status from stored facts; the recovery path itself is {@link RecoveryPath}. Pure. */
public final class FinalStageResolver {

  public static final int REQUIRED_CLUES = RecoveryPath.ROOM_KEYS;

  private FinalStageResolver() {}

  /**
   * @param finalEverOpened the admin has opened the final
   * @param keysAtOpen K: the room keys the team held when the final opened, 0..4
   * @param recoveryFinished every recovery level the team needs is passed (ignored when it needs none)
   * @param finalised the team's final is over (suspect submitted, time up, or the final closed)
   */
  public static FinalStage resolve(boolean finalEverOpened, int keysAtOpen, boolean recoveryFinished, boolean finalised) {
    boolean needsRecovery = !RecoveryPath.requiredLevels(keysAtOpen).isEmpty();
    if (!finalEverOpened) return FinalStage.LOCKED;
    if (finalised) return FinalStage.COMPLETED;
    return needsRecovery && !recoveryFinished ? FinalStage.RECOVERY : FinalStage.IN_FINAL;
  }
}
