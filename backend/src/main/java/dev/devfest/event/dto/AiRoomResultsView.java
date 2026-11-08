package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The AI room results for its manager (or ADMIN). Teams that took part (ran or saved something, or were evaluated),
 * ranked by total (desc) then by their last saved stage (earlier first); disqualified teams are listed last without
 * a rank. Points have two decimals. {@code passScore}: the room's pass score now, locked once a team was evaluated.
 */
public record AiRoomResultsView(
  long roomId,
  String roomName,
  Instant generatedAt,
  int maxPoints,
  int passScore,
  boolean passScoreLocked,
  List<StageColumn> stages,
  List<TeamResult> teams
) {
  /** {@code maxPoints}: null when the room has no content. */
  public record StageColumn(int number, String title, BigDecimal maxPoints) {}

  /** IN_PROGRESS until the team's evaluation (last stage or Time's up); then PASSED or NOT_PASSED for good. */
  public enum Status {
    IN_PROGRESS,
    PASSED,
    NOT_PASSED,
  }

  /**
   * {@code stagePoints}: one per stage column, null when not saved. {@code passScore}: the one the team was
   * evaluated against (the room's current one while in progress). {@code clueGranted}: an evaluated team holds the
   * room's clue now (a manual verdict override can make it differ from {@code status}); false while in progress.
   */
  public record TeamResult(
    Integer rank,
    long teamId,
    String teamName,
    GroupCode groupCode,
    boolean disqualified,
    List<BigDecimal> stagePoints,
    BigDecimal total,
    int stagesSaved,
    Instant lastSavedAt,
    int passScore,
    Status status,
    Instant evaluatedAt,
    boolean clueGranted
  ) {}
}
