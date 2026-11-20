package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.recovery.ArduinoCheck.TestResult;
import dev.devfest.event.domain.recovery.RecoveryLevel;
import dev.devfest.event.domain.recovery.ThresholdMetrics.Metrics;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The answer to one recovery check (an ML stage or an Arduino run). {@code message} is the stage's neutral message;
 * {@code tests} (Arduino only) is PASS/FAIL per test; {@code metrics} (threshold stage only) are the metrics at the sent
 * threshold; {@code token} is the level's cosmetic token when this check passed the level. {@code recoveryFinished} and
 * {@code status} are the team's state now. It never says which part of an answer was wrong.
 */
public record RecoveryCheckResult(
  UUID checkId,
  RecoveryLevel level,
  int stage,
  boolean passed,
  String message,
  List<TestResult> tests,
  Metrics metrics,
  String token,
  boolean recoveryFinished,
  FinalStage status,
  Instant receivedAt
) {}
