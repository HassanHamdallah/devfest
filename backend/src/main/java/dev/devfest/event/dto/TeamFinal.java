package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalScreen;
import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.RoundPhase;
import java.time.Instant;
import java.util.List;

/**
 * The team's final, always built from the server's state (a refresh resumes exactly here). Parts that belong to one
 * screen are null (or empty) elsewhere: {@code mission} on MISSION and CASE; {@code clues}, {@code keyIndex},
 * {@code suspects} and {@code terminal} on CASE only. It never carries a report of an unsolved clue, a passphrase or its
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
  int cluesCollected,
  int waitSeconds,
  Unlock unlock,
  RecoveryChallenge recovery,
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
  /** The four inputs, labelled with the real room names in room order. */
  public record Unlock(List<String> roomNames) {}

  public record RecoveryChallenge(String question, int attempts) {}

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

  /** The last screen: team, status and time; never whether anything was correct. */
  public record Submission(String teamName, FinalStage status, Instant at, String message) {}
}
