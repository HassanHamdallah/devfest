package dev.devfest.event.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A room's 15 points. The base is the room's own score on the 15-point scale (AI: its 0..100 total x 0.15; Linux and
 * Problem Solving: their /15 score; any other room: the manager's typed score). Hints and cheating only subtract
 * points: MAX(0, base - 2 x active hints in that room - 7.5 if an active cheating violation exists for that room),
 * rounded to 2 decimals. Pure; computed on every read, never stored.
 */
public final class RoomScoring {

  public static final BigDecimal ROOM_MAX = new BigDecimal("15");
  private static final BigDecimal AI_FACTOR = new BigDecimal("0.15");

  private RoomScoring() {}

  /** AI: total/100 x 0.15 written as total x 0.15 (so 99 gives 14.85), rounded to 2 decimals. */
  public static BigDecimal aiBase(BigDecimal total) {
    return round(total.multiply(AI_FACTOR));
  }

  /** @param base the room's base score (0..15), already rounded */
  public static BigDecimal roomScore(BigDecimal base, int activeHints, boolean activeCheating) {
    BigDecimal score = base
      .subtract(DeductionKind.HINT.points().multiply(BigDecimal.valueOf(activeHints)))
      .subtract(activeCheating ? DeductionKind.CHEATING.points() : BigDecimal.ZERO);
    return round(score.max(BigDecimal.ZERO));
  }

  public static BigDecimal round(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }
}
