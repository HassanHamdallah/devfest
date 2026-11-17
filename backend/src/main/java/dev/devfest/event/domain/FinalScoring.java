package dev.devfest.event.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The final's 40 points. Clues (max 15): 3.75 for each of the four evidence files the team decrypted. Suspect: 10 for
 * the correct one. Time (max 15): t = active minutes from the final's start to the suspect submission; t <= fullUntil
 * gives 15, otherwise MAX(0, 40 - t), rounded to 2 decimals. Time points need all four clues solved AND a submitted
 * suspect, otherwise they are 0. Pure; computed on every read.
 */
public final class FinalScoring {

  public static final BigDecimal CLUE_POINTS = new BigDecimal("3.75");
  public static final BigDecimal SUSPECT_POINTS = new BigDecimal("10");
  public static final BigDecimal TIME_MAX = new BigDecimal("15");
  public static final BigDecimal FINAL_MAX = new BigDecimal("40");
  public static final int FINAL_MINUTES = 40;

  /**
   * @param cluesSolved 0..4
   * @param activeSeconds the active seconds at the suspect submission; null when no suspect was submitted
   * @param suspectCorrect only meaningful when {@code activeSeconds} is not null
   */
  public record Input(int cluesSolved, Integer activeSeconds, boolean suspectCorrect, int fullUntilMinutes) {}

  public record Score(BigDecimal clues, BigDecimal suspect, BigDecimal time, BigDecimal total) {}

  private FinalScoring() {}

  public static Score score(Input in) {
    if (in.cluesSolved() < 0 || in.cluesSolved() > ShipmentContent.CLUE_COUNT) {
      throw new IllegalArgumentException("Solved clues must be 0..4, was " + in.cluesSolved());
    }
    BigDecimal clues = CLUE_POINTS.multiply(BigDecimal.valueOf(in.cluesSolved()));
    boolean submitted = in.activeSeconds() != null;
    BigDecimal suspect = submitted && in.suspectCorrect() ? SUSPECT_POINTS : BigDecimal.ZERO;
    boolean timed = submitted && in.cluesSolved() == ShipmentContent.CLUE_COUNT;
    BigDecimal time = timed ? timePoints(in.activeSeconds(), in.fullUntilMinutes()) : BigDecimal.ZERO;
    return new Score(
      RoomScoring.round(clues),
      RoomScoring.round(suspect),
      RoomScoring.round(time),
      RoomScoring.round(clues.add(suspect).add(time))
    );
  }

  /** 15 up to {@code fullUntilMinutes} of active time, then MAX(0, 40 - t) with t in minutes (exact, then rounded). */
  public static BigDecimal timePoints(int activeSeconds, int fullUntilMinutes) {
    if (activeSeconds <= fullUntilMinutes * 60L) return RoomScoring.round(TIME_MAX);
    BigDecimal minutes = BigDecimal.valueOf(activeSeconds).divide(BigDecimal.valueOf(60), 10, RoundingMode.HALF_UP);
    return RoomScoring.round(BigDecimal.valueOf(FINAL_MINUTES).subtract(minutes).max(BigDecimal.ZERO).min(TIME_MAX));
  }
}
