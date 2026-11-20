package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalPhase;
import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.recovery.RecoveryLevel;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One active team on the admin's live final table: its status, the stage it is on, how many attempts it made, the
 * phase of the team's own case while it is on the case (null otherwise), its recovery (the keys it held when the final
 * opened, its path NONE / ML / ML_ARDUINO, the level and stage it is on, its checks, when it finished and its crypto
 * window, all on the final's active clock in seconds), evidence files it decrypted (0 to 4), its suspect and the parts of
 * its final score. Admin only: it carries the suspect's correctness.
 */
public record FinalBoardRow(
  long teamId,
  String teamName,
  GroupCode groupCode,
  boolean disqualified,
  FinalStage status,
  String stage,
  FinalPhase phase,
  int clues,
  int cluesSolved,
  int unlockAttempts,
  int unlockWrong,
  int passphraseAttempts,
  int passphraseWrong,
  int recoveryChecks,
  int keysHeld,
  String recoveryPath,
  RecoveryLevel recoveryLevel,
  Integer recoveryStage,
  Integer recoveryFinishedAtSeconds,
  Integer cryptoFromSeconds,
  Integer cryptoToSeconds,
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
