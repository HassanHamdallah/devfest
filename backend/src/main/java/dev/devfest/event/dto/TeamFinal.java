package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalScreen;
import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.RoundPhase;
import java.time.Instant;
import java.util.List;

/**
 * The team's final, always built from the server's state (a refresh resumes exactly here). Parts that belong to one
 * screen are null (or empty) elsewhere. It never carries a plaintext M, a private exponent, p, q, a verdict's
 * correctness, the correct suspect, a score or another team's data. {@code evidence} is the per-room clue list of the
 * team board (rules section 17), {@code notebook} the crypto evidence revealed so far.
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
  LayerPrompt layer,
  List<NotebookEntry> notebook,
  List<FinalSuspect> suspects,
  Submitted submitted,
  boolean resultsPublished,
  List<RoomEvidence> evidence
) {
  /** The four inputs, labelled with the real room names in room order. */
  public record Unlock(List<String> roomNames) {}

  public record RecoveryChallenge(String question, int attempts) {}

  public record Mission(String story, String equipment) {}

  /** Only the ciphertext C and the modulus n. */
  public record LayerPrompt(int layer, long c, long n, int solvedLayers) {}

  /** {@code signature} and {@code verdict*} exist only for the signed reports E03 and E04. */
  public record NotebookEntry(
    String id,
    String text,
    String claimedSigner,
    Signature signature,
    boolean verdictOpen,
    String yourVerdict
  ) {}

  public record Signature(long s, long e, long n, long h) {}

  public record FinalSuspect(int id, String name) {}

  /** The last screen: team, status and time; never whether anything was correct. */
  public record Submitted(String teamName, FinalStage status, Instant at, String message) {}
}
