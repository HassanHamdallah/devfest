package dev.devfest.event.dto;

import java.time.Instant;

/**
 * One team in the organizers' list of Final Investigation Reports. {@code submitted}: the team submitted its suspect;
 * {@code correct} is the suspect's correctness (admin only); {@code activeSeconds} is the final's active time at the
 * submission (the page shows it as the minute). The evidence and explanation are null where the team wrote none.
 */
public record FinalReportView(
  long teamId,
  String teamName,
  boolean submitted,
  String suspectId,
  String suspectName,
  Boolean correct,
  Instant submittedAt,
  Integer activeSeconds,
  String evidence1,
  String evidence2,
  String evidence3,
  String evidence4,
  String explanation
) {}
