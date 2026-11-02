package dev.devfest.event.dto;

import dev.devfest.event.domain.ClueOverride;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One active team on a room board; {@code penalties} are this team's penalties in this room only. */
public record RoomTeamRow(
  long teamId,
  String teamName,
  GroupCode groupCode,
  boolean disqualified,
  /** The room this team was sent to this round by the admin instead of its rotation room, or null. */
  RoomCode exceptionRoom,
  boolean scheduledNow,
  int scheduledRound,
  SubmissionDetail latestSubmission,
  int submissionCount,
  JudgementView judgement,
  List<PenaltyView> penalties,
  int activePenaltyMinutes,
  boolean hintPenaltyMismatch
) {
  /**
   * The latest submission with its clue verdict: {@code clueCorrect} at submission time, {@code autoCorrect}
   * against the current key, the manager's {@code override}, and {@code effectiveCorrect}, which the team sees.
   */
  public record SubmissionDetail(
    UUID id,
    int round,
    String technicalOutput,
    String clueAnswer,
    boolean clueCorrect,
    Instant receivedAt,
    boolean autoCorrect,
    ClueOverride override,
    boolean effectiveCorrect,
    long verdictVersion,
    String overrideByName,
    Instant overrideAt
  ) {}
}
