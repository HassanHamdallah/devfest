package dev.devfest.event.dto;

import dev.devfest.event.domain.TerminalFormStatus;
import java.time.Instant;

/**
 * A team's own form as the server has it. {@code status} is all the team learns about its state: never whether an
 * answer is right, a score, or a key. {@code submittedAt} is set once the form is locked.
 */
public record TerminalFormView(
  long roomId,
  TerminalFormStatus status,
  String path,
  String username,
  String fileSize,
  String pattern,
  String finalClue,
  Instant savedAt,
  Instant submittedAt
) {}
