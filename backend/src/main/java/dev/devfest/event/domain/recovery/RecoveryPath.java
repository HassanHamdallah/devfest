package dev.devfest.event.domain.recovery;

import dev.devfest.event.domain.FinalPhase;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A team's recovery path in the final, from K = the room keys it held when the final opened: 4 keys need no recovery,
 * 3 keys need ML, 0 to 2 keys need ML then ARDUINO. Levels and their stages are played in order, each stage passed once,
 * never skipped. Pure.
 */
public final class RecoveryPath {

  public static final int ROOM_KEYS = 4;

  /** One stage of one level (stage 1..{@link RecoveryLevel#stages()}). */
  public record Step(RecoveryLevel level, int stage) {
    public Step {
      if (stage < 1 || stage > level.stages()) throw new IllegalArgumentException(level + " has no stage " + stage);
    }
  }

  private RecoveryPath() {}

  /** The levels a team with {@code keysAtOpen} keys must pass, in order. */
  public static List<RecoveryLevel> requiredLevels(int keysAtOpen) {
    if (keysAtOpen < 0 || keysAtOpen > ROOM_KEYS) throw new IllegalArgumentException("Room keys must be 0..4, was " + keysAtOpen);
    if (keysAtOpen == ROOM_KEYS) return List.of();
    if (keysAtOpen == ROOM_KEYS - 1) return List.of(RecoveryLevel.ML);
    return List.of(RecoveryLevel.ML, RecoveryLevel.ARDUINO);
  }

  /** The first step not passed yet on the required path; empty once every required level is passed (or none is). */
  public static Optional<Step> current(List<RecoveryLevel> required, Set<Step> passed) {
    for (RecoveryLevel level : required) {
      for (int stage = 1; stage <= level.stages(); stage++) {
        Step step = new Step(level, stage);
        if (!passed.contains(step)) return Optional.of(step);
      }
    }
    return Optional.empty();
  }

  /** True when {@code step} is the last stage of the last required level: passing it finishes the recovery. */
  public static boolean isLast(List<RecoveryLevel> required, Step step) {
    return !required.isEmpty() && required.getLast() == step.level() && step.stage() == step.level().stages();
  }

  /** The path's short name for the organizers' table: NONE (4 keys), ML (3 keys) or ML_ARDUINO (0 to 2 keys). */
  public static String pathCode(int keysAtOpen) {
    List<RecoveryLevel> levels = requiredLevels(keysAtOpen);
    return levels.isEmpty() ? "NONE" : levels.size() == 1 ? "ML" : "ML_ARDUINO";
  }

  /**
   * A team's decryption window on the final's clock: from its case start S until it decrypted its fourth file
   * ({@code toSeconds}), or until the final ends when it never did ({@code toSeconds} null).
   */
  public record CryptoWindow(int fromSeconds, Integer toSeconds) {}

  /** The window of a team whose case start is S (null while S is not known: its recovery is not finished). */
  public static CryptoWindow cryptoWindow(Integer caseStartSeconds, Integer allDecryptedAtSeconds) {
    return caseStartSeconds == null ? null : new CryptoWindow(caseStartSeconds, allDecryptedAtSeconds);
  }

  /**
   * S: the active final seconds at which the team's case clock starts (its decryption phase opens). 0 without recovery;
   * the moment the last required level was passed otherwise; null while the recovery is not finished.
   */
  public static Integer caseStartSeconds(int keysAtOpen, Integer finishedAtActiveSeconds) {
    return requiredLevels(keysAtOpen).isEmpty() ? Integer.valueOf(0) : finishedAtActiveSeconds;
  }
}
