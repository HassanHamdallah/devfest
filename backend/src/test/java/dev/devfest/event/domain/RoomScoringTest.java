package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Room score = MAX(0, base - 2 x hints - 7.5 if cheating), rounded to 2 decimals. */
class RoomScoringTest {

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }

  private static void assertScore(String expected, String base, int hints, boolean cheating) {
    assertEquals(d(expected), RoomScoring.roomScore(d(base), hints, cheating), base + " h" + hints + " c" + cheating);
  }

  @Test
  void noDeductionKeepsTheBase() {
    assertScore("15.00", "15", 0, false);
    assertScore("12.35", "12.35", 0, false);
  }

  @Test
  void eachHintCostsTwoPoints() {
    assertScore("13.00", "15", 1, false);
    assertScore("11.00", "15", 2, false);
  }

  @Test
  void cheatingCostsSevenAndAHalf() {
    assertScore("7.50", "15", 0, true);
  }

  @Test
  void hintsAndCheatingAddUp() {
    assertScore("5.50", "15", 1, true);
    assertScore("3.50", "15", 2, true);
  }

  @Test
  void theScoreNeverGoesBelowZero() {
    assertScore("0.00", "3", 2, false);
    assertScore("0.00", "7", 0, true);
    assertScore("0.00", "0", 2, true);
    assertScore("0.00", "7.5", 0, true);
    assertScore("0.01", "7.51", 0, true);
  }

  @Test
  void theAiRoomBaseIsItsTotalTimesZeroPointFifteen() {
    assertEquals(d("14.85"), RoomScoring.aiBase(d("99")));
    assertEquals(d("15.00"), RoomScoring.aiBase(d("100")));
    assertEquals(d("0.00"), RoomScoring.aiBase(d("0")));
    assertEquals(d("9.00"), RoomScoring.aiBase(d("60")));
    assertEquals(d("0.15"), RoomScoring.aiBase(d("1")));
    assertEquals(d("0.05"), RoomScoring.aiBase(d("0.33")));
    assertEquals(d("14.85"), RoomScoring.roomScore(RoomScoring.aiBase(d("99")), 0, false));
    assertEquals(d("12.85"), RoomScoring.roomScore(RoomScoring.aiBase(d("99")), 1, false));
  }
}
