package dev.devfest.event.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The final's 40 points. Crypto (max 15): each correct layer 2.5 and each correct verdict 2.5 (four layers, two
 * verdicts). Suspect: 10 for the correct one. Time (max 15): t = active minutes from the final's start to the suspect
 * submission; t <= fullUntil gives 15, otherwise MAX(0, 40 - t), rounded to 2 decimals. Time points need all four
 * layers solved and a submitted suspect, otherwise they are 0. Pure; computed on every read.
 */
public final class FinalScoring {

  public static final BigDecimal LAYER_POINTS = new BigDecimal("2.5");
  public static final BigDecimal VERDICT_POINTS = new BigDecimal("2.5");
  public static final BigDecimal SUSPECT_POINTS = new BigDecimal("10");
  public static final BigDecimal TIME_MAX = new BigDecimal("15");
  public static final BigDecimal FINAL_MAX = new BigDecimal("40");
  public static final int FINAL_MINUTES = 40;

  /**
   * @param activeSeconds the active seconds at the suspect submission; null when no suspect was submitted
   * @param suspectCorrect only meaningful when {@code activeSeconds} is not null
   */
  public record Input(int layersCorrect, int verdictsCorrect, Integer activeSeconds, boolean suspectCorrect, int fullUntilMinutes) {}

  public record Score(BigDecimal layers, BigDecimal verdicts, BigDecimal crypto, BigDecimal suspect, BigDecimal time, BigDecimal total) {}

  private FinalScoring() {}

  public static Score score(Input in) {
    BigDecimal layers = LAYER_POINTS.multiply(BigDecimal.valueOf(in.layersCorrect()));
    BigDecimal verdicts = VERDICT_POINTS.multiply(BigDecimal.valueOf(in.verdictsCorrect()));
    BigDecimal suspect = in.activeSeconds() != null && in.suspectCorrect() ? SUSPECT_POINTS : BigDecimal.ZERO;
    boolean timed = in.activeSeconds() != null && in.layersCorrect() >= CryptoContent.LAYER_COUNT;
    BigDecimal time = timed ? timePoints(in.activeSeconds(), in.fullUntilMinutes()) : BigDecimal.ZERO;
    BigDecimal crypto = layers.add(verdicts);
    return new Score(
      RoomScoring.round(layers),
      RoomScoring.round(verdicts),
      RoomScoring.round(crypto),
      RoomScoring.round(suspect),
      RoomScoring.round(time),
      RoomScoring.round(crypto.add(suspect).add(time))
    );
  }

  /** 15 up to {@code fullUntilMinutes} of active time, then MAX(0, 40 - t) with t in minutes (exact, then rounded). */
  public static BigDecimal timePoints(int activeSeconds, int fullUntilMinutes) {
    if (activeSeconds <= fullUntilMinutes * 60L) return RoomScoring.round(TIME_MAX);
    BigDecimal minutes = BigDecimal.valueOf(activeSeconds).divide(BigDecimal.valueOf(60), 10, RoundingMode.HALF_UP);
    return RoomScoring.round(BigDecimal.valueOf(FINAL_MINUTES).subtract(minutes).max(BigDecimal.ZERO).min(TIME_MAX));
  }
}
