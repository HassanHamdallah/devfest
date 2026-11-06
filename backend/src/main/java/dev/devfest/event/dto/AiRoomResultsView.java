package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.QuizSection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The AI room results for its manager (or ADMIN). Teams that took part (answered, or were evaluated), ranked by
 * total (desc) then by their last answer (earlier first); disqualified teams are listed last without a rank. Scores
 * have two decimals. {@code passScore}: the room's pass score now, locked once a team was evaluated.
 */
public record AiRoomResultsView(
  long roomId,
  String roomName,
  Instant generatedAt,
  int maxPoints,
  int passScore,
  boolean passScoreLocked,
  List<RepairColumn> repairs,
  List<TeamResult> teams,
  List<QuestionStat> questions
) {
  public record RepairColumn(long id, int number, String title, int points) {}

  /** IN_PROGRESS until the team's evaluation (last repair or Time's up); then PASSED or NOT_PASSED for good. */
  public enum Status {
    IN_PROGRESS,
    PASSED,
    NOT_PASSED,
  }

  /**
   * {@code repairScores}: one per repair column, null when not submitted. {@code passScore}: the one the team was
   * evaluated against (the room's current one while in progress). {@code clueGranted}: an evaluated team holds the
   * room's clue now (a manual verdict override or clue grant can make it differ from {@code status}); false while
   * in progress.
   */
  public record TeamResult(
    Integer rank,
    long teamId,
    String teamName,
    GroupCode groupCode,
    boolean disqualified,
    Map<QuizSection, BigDecimal> mcqBySection,
    BigDecimal mcqTotal,
    List<BigDecimal> repairScores,
    BigDecimal repairTotal,
    BigDecimal total,
    int answered,
    int repairsSubmitted,
    Instant mcqFinishedAt,
    Instant lastAnswerAt,
    int passScore,
    Status status,
    Instant evaluatedAt,
    boolean clueGranted
  ) {}

  /** {@code percentCorrect}: correct answers among the teams that took part (0–100, whole percent). */
  public record QuestionStat(long id, QuizSection section, String prompt, int answered, int correct, int percentCorrect) {}
}
