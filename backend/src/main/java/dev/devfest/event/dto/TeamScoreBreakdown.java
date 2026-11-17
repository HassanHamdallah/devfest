package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One team's full score out of 100: four room scores (15 each) and the final (40). Organizers and the admin only;
 * a member never receives this before the results are published, and never the deduction details.
 */
public record TeamScoreBreakdown(
  long teamId,
  String teamName,
  GroupCode groupCode,
  boolean disqualified,
  int clues,
  List<RoomCode> clueRooms,
  List<RoomScoreLine> rooms,
  BigDecimal roomsTotal,
  FinalScoreLine finalScore,
  BigDecimal total
) {
  /**
   * {@code source}: AI, LINUX, CONTEST or MANUAL (how the base was obtained); {@code base}: the room's own score on
   * the 15-point scale; {@code score}: MAX(0, base - hintDeduction - cheatingDeduction).
   */
  public record RoomScoreLine(
    long roomId,
    RoomCode code,
    String name,
    String source,
    BigDecimal base,
    int hints,
    boolean cheating,
    BigDecimal hintDeduction,
    BigDecimal cheatingDeduction,
    BigDecimal score
  ) {}

  /**
   * {@code cluesSolved}: evidence files decrypted (0 to 4); {@code clues}: their points (3.75 each); {@code suspect},
   * {@code time}, {@code total}: the other parts of the final's 40 points.
   */
  public record FinalScoreLine(
    FinalStage status,
    int cluesSolved,
    BigDecimal clues,
    boolean suspectSubmitted,
    boolean suspectCorrect,
    BigDecimal suspect,
    Integer activeSeconds,
    BigDecimal time,
    BigDecimal total,
    Instant finishedAt
  ) {}
}
