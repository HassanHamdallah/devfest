package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.GroupCode;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One active team on the admin's live final table: its status, the stage it is on, how many attempts it made, what it
 * answered and the parts of its final score. Admin only: it carries the verdicts' and the suspect's correctness.
 * {@code verdictE03} / {@code verdictE04} are the team's own YES / NO (null while not given).
 */
public record FinalBoardRow(
  long teamId,
  String teamName,
  GroupCode groupCode,
  boolean disqualified,
  FinalStage status,
  String stage,
  int clues,
  int layersSolved,
  int unlockAttempts,
  int unlockWrong,
  int layerAttempts,
  int layerWrong,
  int recoveryAttempts,
  String verdictE03,
  Boolean verdictE03Correct,
  String verdictE04,
  Boolean verdictE04Correct,
  String suspect,
  Boolean suspectCorrect,
  Integer activeSeconds,
  Instant finishedAt,
  String finishedBy,
  BigDecimal layersPoints,
  BigDecimal verdictsPoints,
  BigDecimal suspectPoints,
  BigDecimal timePoints,
  BigDecimal finalScore
) {}
