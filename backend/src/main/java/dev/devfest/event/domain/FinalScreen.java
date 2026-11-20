package dev.devfest.event.domain;

/**
 * The screen a team's final shows now (RECOVERY: its recovery levels, before UNLOCK), derived on the server; the page just renders it. CASE is the case itself (the
 * four evidence files, the key index and the suspects) from "Start" until the suspect is submitted or the team is
 * finalised.
 */
public enum FinalScreen {
  LOCKED,
  RECOVERY,
  UNLOCK,
  MISSION,
  CASE,
  SUBMITTED
}
