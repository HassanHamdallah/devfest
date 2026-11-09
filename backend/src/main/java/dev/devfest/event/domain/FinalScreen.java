package dev.devfest.event.domain;

/** The screen a team's final shows now, derived on the server; the page just renders it. */
public enum FinalScreen {
  LOCKED,
  NOT_QUALIFIED,
  RECOVERY,
  UNLOCK,
  MISSION,
  LAYER,
  SUSPECTS,
  SUBMITTED
}
