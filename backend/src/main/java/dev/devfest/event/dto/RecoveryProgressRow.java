package dev.devfest.event.dto;

import dev.devfest.event.domain.recovery.RecoveryLevel;
import java.time.Instant;
import java.util.List;

/**
 * One active team on the admin's recovery table: K, the levels it must pass, the stage it is on (null once finished or
 * when none is needed), its checks in all and on the current stage, its latest check, and when it finished (time and
 * active final seconds, null until then).
 */
public record RecoveryProgressRow(
  long teamId,
  String teamName,
  int keysAtOpen,
  List<RecoveryLevel> levels,
  RecoveryLevel currentLevel,
  Integer currentStage,
  boolean finished,
  int checks,
  int stageChecks,
  Instant lastCheckAt,
  Instant finishedAt,
  Integer finishedActiveSeconds
) {}
