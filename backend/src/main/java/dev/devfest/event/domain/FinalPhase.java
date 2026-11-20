package dev.devfest.event.domain;

/**
 * The two phases of a team's case. There is no clock in the rule: a team is in DECRYPTION until it has decrypted all
 * four evidence files (the moment its fourth passphrase was accepted is stored once, as active final seconds) and in
 * INVESTIGATION from then on. Phase 1 is never closed by time: the decryption material stays available until the final
 * itself ends. {@code phase1Minutes} of the content is only an estimate shown to the team. Pure.
 */
public enum FinalPhase {
  DECRYPTION,
  INVESTIGATION;

  /** The content's estimate of the decryption phase (minutes); it closes nothing. */
  public static final int DEFAULT_PHASE1_MINUTES = 15;
  public static final int MIN_PHASE1_MINUTES = 1;
  public static final int MAX_PHASE1_MINUTES = 39;

  /**
   * THE phase rule, used everywhere (team view, suspect, passphrases, terminal package, admin board).
   *
   * @param allDecryptedAtSeconds the active final second the team's fourth passphrase was accepted; null while it has
   *     not decrypted all four files
   */
  public static FinalPhase of(Integer allDecryptedAtSeconds) {
    return allDecryptedAtSeconds == null ? DECRYPTION : INVESTIGATION;
  }
}
