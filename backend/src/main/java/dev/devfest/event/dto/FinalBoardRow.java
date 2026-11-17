package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.GroupCode;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One active team on the admin's live final table: its status, the stage it is on, how many attempts it made, the
 * evidence files it decrypted (0 to 4), its suspect and the parts of its final score. Admin only: it carries the
 * suspect's correctness.
 */
public record FinalBoardRow(
  long teamId,
  String teamName,
  GroupCode groupCode,
  boolean disqualified,
  FinalStage status,
  String stage,
  int clues,
  int cluesSolved,
  int unlockAttempts,
  int unlockWrong,
  int passphraseAttempts,
  int passphraseWrong,
  int recoveryAttempts,
  String suspect,
  Boolean suspectCorrect,
  Integer activeSeconds,
  Instant finishedAt,
  String finishedBy,
  BigDecimal cluePoints,
  BigDecimal suspectPoints,
  BigDecimal timePoints,
  BigDecimal finalScore
) {}
