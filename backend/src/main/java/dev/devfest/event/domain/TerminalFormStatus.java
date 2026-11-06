package dev.devfest.event.domain;

/** All a team is ever told about its form: nothing saved yet, Saved, or Submitted (never an outcome). */
public enum TerminalFormStatus {
  EMPTY,
  SAVED,
  SUBMITTED,
}
