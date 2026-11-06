package dev.devfest.event.repository;

import dev.devfest.event.domain.QuizSection;
import java.time.Instant;
import java.util.List;

/**
 * One AI room question with its options (by authoring position) and the correct flags; organizers only.
 * {@code answered}: a team has answered it, so its content is locked.
 */
public record QuizQuestionRecord(
  long id,
  long roomId,
  QuizSection section,
  int position,
  String prompt,
  int points,
  String explanation,
  Instant updatedAt,
  boolean answered,
  List<Option> options
) {
  public record Option(long id, int position, String text, boolean correct) {}

  /** Index (in authoring order) of the correct option; -1 if none (never stored that way). */
  public int correctIndex() {
    for (int i = 0; i < options.size(); i++) if (options.get(i).correct()) return i;
    return -1;
  }
}
