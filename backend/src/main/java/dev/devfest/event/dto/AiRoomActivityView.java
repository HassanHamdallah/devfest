package dev.devfest.event.dto;

import dev.devfest.event.domain.QuizSection;
import dev.devfest.event.domain.QuizWindow;
import dev.devfest.event.domain.RoundPhase;
import java.time.Instant;
import java.util.List;

/**
 * The AI room activity as its team sees it (rules §19). Never carries correct options, the correct snippet order,
 * explanations, points, scores or the pass score. {@code questions} are sent only while the MCQ part is open, in
 * the team's order; {@code repairs} only once the repairs are open (with instructions and snippets only while they
 * can be submitted). Once the team is evaluated only {@code result} matters: questions and repairs are empty.
 *
 * @param mcqRemainingSeconds MCQ time left at {@code serverNow} (0 once the MCQ part is over)
 * @param result null until the team is evaluated
 */
public record AiRoomActivityView(
  long roomId,
  String roomName,
  QuizWindow.Stage stage,
  RoundPhase phase,
  Instant serverNow,
  int mcqMinutes,
  int mcqRemainingSeconds,
  boolean mcqFinished,
  int questionCount,
  int answeredCount,
  int repairCount,
  int submittedCount,
  List<Section> sections,
  List<Question> questions,
  List<Repair> repairs,
  Result result
) {
  /** {@code paceSeconds}: the suggested time per question ("Easy · aim 12 s"). */
  public record Section(QuizSection section, String label, int paceSeconds, int questions) {}

  /** {@code number}: 1-based place in the team's order; {@code selectedOptionId}: the team's saved answer. */
  public record Question(long id, int number, QuizSection section, String prompt, List<Option> options, Long selectedOptionId) {}

  public record Option(long id, String text) {}

  /** Snippets in the team's shuffled order, or as submitted once {@code submitted}. */
  public record Repair(long id, int number, String title, String instructions, List<Snippet> snippets, boolean submitted) {}

  public record Snippet(long id, String code) {}

  /**
   * The team's outcome: {@code clueUnlocked} when it holds the room's clue, with {@code clue} the room's current
   * official clue; otherwise both empty ("You did not reach the required score for this room's clue.").
   */
  public record Result(boolean clueUnlocked, String clue) {}
}
