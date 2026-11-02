package dev.devfest.event.dto;

import dev.devfest.event.domain.QuizSection;
import dev.devfest.event.domain.RoomActivity;
import java.util.List;

/**
 * The AI room content for its manager (or ADMIN): every question with its correct option and explanation, every
 * repair with its snippets in the correct order, totals per section and the planning warnings.
 *
 * @param editable false once the final has opened (all content is read-only then)
 * @param answered a team has answered something, so the content can no longer be replaced by an import
 * @param passScoreLocked a team was evaluated, so the pass score can no longer change
 */
public record QuizEditorView(
  long roomId,
  String roomName,
  RoomActivity activity,
  int mcqMinutes,
  int passScore,
  boolean passScoreLocked,
  boolean editable,
  boolean answered,
  List<Question> questions,
  List<Repair> repairs,
  List<SectionTotal> sections,
  int mcqPoints,
  int repairPoints,
  int totalPoints,
  List<String> warnings
) {
  /** {@code locked}: a team answered it, so it can no longer be changed or deleted. */
  public record Question(
    long id,
    QuizSection section,
    String prompt,
    List<String> options,
    int correctOption,
    int points,
    String explanation,
    boolean locked
  ) {}

  /** {@code number}: 1-based. {@code snippets} in their correct order. */
  public record Repair(long id, int number, String title, String instructions, int points, List<String> snippets, boolean locked) {}

  public record SectionTotal(QuizSection section, String label, int questions, int planned, int points) {}
}
