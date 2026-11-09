package dev.devfest.event.domain;

/**
 * A team's status in the final: Not qualified (0-2 clues), Recovery (3 clues, until the Recovery Challenge is solved),
 * In final (4 clues, until finalised) and Completed (suspect submitted, time up or final closed). LOCKED is the
 * time before the admin opens the final; see {@link FinalStageResolver}.
 */
public enum FinalStage {
  LOCKED,
  NOT_QUALIFIED,
  RECOVERY,
  IN_FINAL,
  COMPLETED
}
