package dev.devfest.event.domain;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The five answers of a LINUX_TERMINAL room's form (rules §18.7). Blank values are allowed; each is limited to
 * 500 UTF-8 bytes (the clue answer's own limit). The final answer is recorded as an ordinary room submission whose
 * technical output is the server-made {@link #summary}.
 */
public record TerminalFormValues(
  String path,
  String username,
  String fileSize,
  String pattern,
  String finalClue
) {
  public static final int MAX_BYTES = 500;

  public static TerminalFormValues empty() {
    return new TerminalFormValues("", "", "", "", "");
  }

  /** True when at least one field has something other than blanks: only such a form is submitted at Time's up. */
  public boolean hasContent() {
    return !path.isBlank() || !username.isBlank() || !fileSize.isBlank() || !pattern.isBlank() || !finalClue.isBlank();
  }

  /** Why the values cannot be stored (a field too long, or a NUL character), or empty when they can. */
  public Optional<String> problem() {
    String[] labels = { "PATH", "USERNAME", "FILE_SIZE", "PATTERN", "Final clue" };
    String[] values = { path, username, fileSize, pattern, finalClue };
    for (int i = 0; i < values.length; i++) {
      if (values[i].indexOf('\0') >= 0) return Optional.of(labels[i] + " must be text.");
      if (values[i].getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
        return Optional.of(labels[i] + " is too long (max " + MAX_BYTES + " bytes).");
      }
    }
    return Optional.empty();
  }

  /**
   * The technical output of the recorded submission: every field with its label, and how it came about. At most
   * about 2.7 KB, inside the 4000-byte limit of a submission's technical output.
   */
  public String summary(TerminalFormCause cause) {
    return "Linux terminal form (" + (cause == TerminalFormCause.TIME_UP ? "saved by the team, submitted at Time's up" : "submitted by the team") + ")\n"
      + "PATH: " + path + "\n"
      + "USERNAME: " + username + "\n"
      + "FILE_SIZE: " + fileSize + "\n"
      + "PATTERN: " + pattern + "\n"
      + "Final clue: " + finalClue;
  }
}
