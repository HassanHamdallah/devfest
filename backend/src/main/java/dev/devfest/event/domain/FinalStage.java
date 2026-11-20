package dev.devfest.event.domain;

/**
 * A team's status in the final: Recovery (it held fewer than 4 room keys when the final opened and has not passed its
 * recovery levels yet), In final (until finalised) and Completed (suspect submitted, time up or final closed). Every
 * team plays. LOCKED is the time before the admin opens the final; see {@link FinalStageResolver}.
 */
public enum FinalStage {
  LOCKED,
  RECOVERY,
  IN_FINAL,
  COMPLETED
}
