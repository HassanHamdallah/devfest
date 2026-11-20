package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalPhase;
import dev.devfest.event.domain.FinalReport;
import dev.devfest.event.domain.FinalScreen;
import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.RoundPhase;
import dev.devfest.event.domain.recovery.RecoveryLevel;
import java.time.Instant;
import java.util.List;

/**
 * The team's final, always built from the server's state (a refresh resumes exactly here). Parts that belong to one
 * screen are null (or empty) elsewhere: {@code mission} on MISSION and CASE; {@code clues}, {@code keyIndex},
 * {@code suspects} and {@code terminal} on CASE only; {@code suspects} only in the INVESTIGATION phase and
 * {@code terminal} in both phases (Phase 1 stays open until the final ends). {@code phase} (MISSION and CASE: DECRYPTION until the team has decrypted all four files, then INVESTIGATION) and {@code phase1Minutes} (the content's
 * estimate of the decryption phase, closes nothing) come from the team's stored progress (rules section 10). {@code recovery} (RECOVERY only) names the levels the team must pass; their content is
 * GET /team/final/recovery. {@code unlock} carries the room keys the team recovered, for this team only. It never carries a report of an unsolved clue, a passphrase or its
 * hash, the correct suspect, a score or another team's data. {@code waitSeconds} is the unlock's wait; each clue has its
 * own. {@code submitted}: the team's suspect is recorded. {@code evidence} is the per-room clue list of the team board
 * (rules section 17).
 */
public record TeamFinal(
  FinalStage status,
  FinalScreen screen,
  Instant serverNow,
  Instant finalStartedAt,
  RoundPhase timerPhase,
  Integer remainingSeconds,
  FinalPhase phase,
  Integer phase1Minutes,
  int cluesCollected,
  int waitSeconds,
  Unlock unlock,
  RecoveryPathView recovery,
  Mission mission,
  List<CaseClue> clues,
  List<KeyIndexEntry> keyIndex,
  List<CaseSuspect> suspects,
  TerminalBundleInfo terminal,
  boolean submitted,
  Submission submission,
  boolean resultsPublished,
  List<RoomEvidence> evidence
) {
  /** The four inputs, labelled with the real room names in room order, and the keys the team's recovery granted. */
  public record Unlock(List<String> roomNames, List<RecoveredKey> recoveredKeys) {}

  /** A room key granted by the team's recovery: the room's name and its key value. */
  public record RecoveredKey(String roomName, String key) {}

  /** The recovery levels the team must pass, in order. */
  public record RecoveryPathView(List<RecoveryLevel> levels) {}

  public record Mission(String title, String intro, String phase1, String phase2) {}

  /** One evidence file; {@code report} is present only once the team solved it. */
  public record CaseClue(
    int number,
    String title,
    String encFile,
    String rsaFile,
    String keyReference,
    boolean solved,
    int waitSeconds,
    String report
  ) {}

  public record KeyIndexEntry(String reference, String key) {}

  public record CaseSuspect(String id, String name, String position, String statement) {}

  /**
   * The last screen: team, status and time; never whether anything was correct. {@code suspectId}, {@code suspectName}
   * and {@code report} are the team's own submitted answer and report (null when it submitted none, e.g. time up).
   */
  public record Submission(
    String teamName,
    FinalStage status,
    Instant at,
    String message,
    String suspectId,
    String suspectName,
    FinalReport report
  ) {}
}
