package dev.devfest.event.repository;

import dev.devfest.event.domain.TerminalFormCause;
import dev.devfest.event.domain.TerminalFormValues;
import java.time.Instant;
import java.util.UUID;

/** One terminal_form row; the last three fields are null until the form is submitted. */
public record TerminalFormRecord(
  long teamId,
  long roomId,
  TerminalFormValues values,
  Instant savedAt,
  Instant submittedAt,
  UUID submissionId,
  TerminalFormCause cause
) {
  public boolean submitted() {
    return submittedAt != null;
  }
}
