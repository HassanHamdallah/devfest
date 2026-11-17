package dev.devfest.event.domain;

/**
 * The two phases of the final, decided only by the final timer's active seconds (pauses excluded), so every team is in
 * the same phase at the same moment, whenever it entered the case. DECRYPTION lasts the first {@code phase1Minutes} of
 * active time; INVESTIGATION is everything after, until the final ends. Pure.
 */
public enum FinalPhase {
  DECRYPTION,
  INVESTIGATION;

  public static final int DEFAULT_PHASE1_MINUTES = 15;
  public static final int MIN_PHASE1_MINUTES = 1;
  public static final int MAX_PHASE1_MINUTES = 39;

  /** The phase after {@code activeSeconds} of the final's active time: the boundary itself already belongs to INVESTIGATION. */
  public static FinalPhase at(int activeSeconds, int phase1Minutes) {
    return activeSeconds < phase1Seconds(phase1Minutes) ? DECRYPTION : INVESTIGATION;
  }

  /** Seconds of active time until INVESTIGATION opens; null once it has opened. */
  public static Integer phase1EndsInSeconds(int activeSeconds, int phase1Minutes) {
    int left = phase1Seconds(phase1Minutes) - activeSeconds;
    return left > 0 ? left : null;
  }

  private static int phase1Seconds(int phase1Minutes) {
    return phase1Minutes * 60;
  }
}
