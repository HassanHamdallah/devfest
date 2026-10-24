package dev.devfest.event.domain;

/** Where a team stands in the final; see {@link FinalStageResolver}. */
public enum FinalStage {
  LOCKED,
  NOT_ENOUGH_CLUES,
  RECOVERY,
  MESSAGE,
  FINISHED,
  CLOSED
}
