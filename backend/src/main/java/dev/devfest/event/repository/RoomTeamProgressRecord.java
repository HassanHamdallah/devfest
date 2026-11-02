package dev.devfest.event.repository;

import dev.devfest.event.domain.GroupCode;

/** One active team as seen from one room; {@code latestSubmission} is null when it never submitted there. */
public record RoomTeamProgressRecord(
  long teamId,
  String teamName,
  GroupCode groupCode,
  boolean disqualified,
  RoomSubmissionRecord latestSubmission,
  ClueVerdictRecord latestVerdict,
  int submissionCount,
  RoomJudgementRecord judgement
) {}
