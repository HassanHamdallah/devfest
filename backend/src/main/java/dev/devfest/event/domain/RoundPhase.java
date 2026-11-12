package dev.devfest.event.domain;

/**
 * The phase of a running round (1..4). ASSIGNED, RUNNING and PAUSED are stored; TIME_UP is derived when the
 * timer has been started and no time remains (or the admin ended the round). Rounds 0 and 5 have no phase.
 */
public enum RoundPhase {
  ASSIGNED,
  RUNNING,
  PAUSED,
  TIME_UP;

  public boolean stored() {
    return this != TIME_UP;
  }

  /**
   * Whether the Problem Solving contest link may be handed to a team in this phase (rules §20.3): only while its
   * timer is RUNNING or PAUSED, never before it starts (ASSIGNED) or after it ends (TIME_UP).
   */
  public boolean contestLinkOpen() {
    return this == RUNNING || this == PAUSED;
  }
}
