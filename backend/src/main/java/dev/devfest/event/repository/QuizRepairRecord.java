package dev.devfest.event.repository;

import java.time.Instant;
import java.util.List;

/**
 * One AI room repair with its snippets in their correct order; organizers only (teams get them shuffled).
 * {@code answered}: a team has submitted it, so its content is locked.
 */
public record QuizRepairRecord(
  long id,
  long roomId,
  int position,
  String title,
  String instructions,
  int points,
  Instant updatedAt,
  boolean answered,
  List<Snippet> snippets
) {
  public record Snippet(long id, int correctPosition, String code) {}

  public List<Long> correctOrder() {
    return snippets.stream().map(Snippet::id).toList();
  }
}
