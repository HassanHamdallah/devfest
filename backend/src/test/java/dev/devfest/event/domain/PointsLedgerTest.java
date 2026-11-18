package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.devfest.event.domain.PointsLedger.Entry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PointsLedgerTest {

  private static Entry add(long id, int delta) {
    return new Entry(id, delta, null);
  }

  private static Entry undoOf(long id, long reverses, int delta) {
    return new Entry(id, delta, reverses);
  }

  // ---- the sum ---------------------------------------------------------------------------------

  @Test
  void theTotalIsTheSumOfTheEntries() {
    assertEquals(0, PointsLedger.total(List.of()));
    assertEquals(70, PointsLedger.total(List.of(add(1, 30), add(2, 40))));
    assertEquals(65, PointsLedger.total(List.of(add(1, 30), add(2, 40), add(3, -5))));
    assertEquals(70, PointsLedger.total(List.of(add(1, 30), add(2, 40), add(3, -5), undoOf(4, 3, 5))));
  }

  // ---- the 0..100 clamp ------------------------------------------------------------------------

  @Test
  void aChangeThatLeavesZeroToHundredIsRefusedWithTheCurrentTotal() {
    assertTrue(PointsLedger.problem(85, 15).isEmpty(), "exactly 100 is fine");
    assertTrue(PointsLedger.problem(5, -5).isEmpty(), "exactly 0 is fine");
    assertEquals("The total must stay between 0 and 100 (now 85).", PointsLedger.problem(85, 16).orElseThrow());
    assertEquals("The total must stay between 0 and 100 (now 3).", PointsLedger.problem(3, -4).orElseThrow());
    assertEquals("The total must stay between 0 and 100 (now 0).", PointsLedger.problem(0, -1).orElseThrow());
    assertEquals("The total must stay between 0 and 100 (now 100).", PointsLedger.problem(100, 1).orElseThrow());
  }

  @Test
  void aChangeIsAWholeNumberFromMinusHundredToHundredAndNeverZero() {
    assertEquals("The change cannot be 0.", PointsLedger.problem(10, 0).orElseThrow());
    assertEquals("The change must be from -100 to 100.", PointsLedger.problem(10, 101).orElseThrow());
    assertEquals("The change must be from -100 to 100.", PointsLedger.problem(10, -101).orElseThrow());
    assertTrue(PointsLedger.problem(0, 100).isEmpty());
    assertTrue(PointsLedger.problem(100, -100).isEmpty());
  }

  // ---- undo --------------------------------------------------------------------------------------

  @Test
  void undoTargetsTheNewestChangeThatWasNotUndone() {
    assertEquals(Optional.empty(), PointsLedger.undoTarget(List.of()), "nothing to undo");
    assertEquals(3, PointsLedger.undoTarget(List.of(add(1, 30), add(2, 40), add(3, -5))).orElseThrow().id());
    assertEquals(-5, PointsLedger.undoTarget(List.of(add(1, 30), add(2, 40), add(3, -5))).orElseThrow().delta());
  }

  @Test
  void aSecondUndoStepsBackAndNeverUndoesTheUndo() {
    List<Entry> afterOne = List.of(add(1, 30), add(2, 40), add(3, -5), undoOf(4, 3, 5));
    assertEquals(2, PointsLedger.undoTarget(afterOne).orElseThrow().id(), "the undo row itself is never the target");
    List<Entry> afterTwo = List.of(add(1, 30), add(2, 40), add(3, -5), undoOf(4, 3, 5), undoOf(5, 2, -40));
    assertEquals(1, PointsLedger.undoTarget(afterTwo).orElseThrow().id());
    List<Entry> afterAll = List.of(add(1, 30), undoOf(2, 1, -30));
    assertEquals(Optional.empty(), PointsLedger.undoTarget(afterAll), "everything is undone: nothing left, and the undo row is not undoable");
    assertEquals(0, PointsLedger.total(afterAll));
  }

  @Test
  void undoStepsNeverLeaveTheRange() {
    List<Entry> entries = List.of(add(1, 60), add(2, -60), add(3, 100));
    int total = PointsLedger.total(entries);
    assertEquals(100, total);
    Entry target = PointsLedger.undoTarget(entries).orElseThrow();
    assertTrue(PointsLedger.problem(total, -target.delta()).isEmpty());
  }

  // ---- room score ----------------------------------------------------------------------------------

  @Test
  void theRoomScoreIsTheTotalTimesZeroPointFifteenRoundedToTwoDecimals() {
    assertEquals(new BigDecimal("15.00"), RoomScoring.pointsBase(100));
    assertEquals(new BigDecimal("12.00"), RoomScoring.pointsBase(80));
    assertEquals(new BigDecimal("5.55"), RoomScoring.pointsBase(37));
    assertEquals(new BigDecimal("10.50"), RoomScoring.pointsBase(70));
    assertEquals(new BigDecimal("9.75"), RoomScoring.pointsBase(65));
    assertEquals(new BigDecimal("0.00"), RoomScoring.pointsBase(0));
    assertEquals(new BigDecimal("0.15"), RoomScoring.pointsBase(1));
    assertFalse(RoomScoring.pointsBase(7).scale() != 2);
  }
}
