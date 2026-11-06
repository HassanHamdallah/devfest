package dev.devfest.event.domain;

/** How a team's form was submitted: by the team's Final submit, or by the round's end for a saved form. */
public enum TerminalFormCause {
  SUBMIT,
  TIME_UP,
}
