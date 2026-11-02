package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One active team on a room board; {@code penalties} are this team's penalties in this room only. */
public record RoomTeamRow(
  long teamId,
  String teamName,
  GroupCode groupCode,
  boolean scheduledNow,
  int scheduledRound,
  SubmissionDetail latestSubmission,
  int submissionCount,
  JudgementView judgement,
  List<PenaltyView> penalties,
  int activePenaltyMinutes,
  boolean hintPenaltyMismatch
) {
  public record SubmissionDetail(
    UUID id,
    int round,
    String technicalOutput,
    String clueAnswer,
    boolean clueCorrect,
    Instant receivedAt
  ) {}
}
