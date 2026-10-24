package dev.devfest.event.dto;

import dev.devfest.event.domain.JudgeStatus;
import java.time.Instant;

/** Version 0 with a null reviewer means nobody has judged the team in this room yet. */
public record JudgementView(
  long teamId,
  long roomId,
  JudgeStatus status,
  int hints,
  String note,
  long version,
  String reviewerName,
  Instant updatedAt
) {}
