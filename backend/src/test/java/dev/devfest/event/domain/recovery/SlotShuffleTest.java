package dev.devfest.event.domain.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The per-team order of the missing-behaviour options: stable for a team, different between teams, letters by position. */
class SlotShuffleTest {

  private static final List<String> IDS = List.of("A", "B", "C", "D");

  @Test
  void aTeamAlwaysSeesTheSameOrderOfTheSameOptions() {
    for (long team = 1; team <= 50; team++) {
      List<String> order = SlotShuffle.order(IDS, team);
      assertEquals(order, SlotShuffle.order(IDS, team));
      assertEquals(Set.copyOf(IDS), Set.copyOf(order));
      assertEquals(4, order.size());
    }
  }

  @Test
  void teamsSeeDifferentOrdersAndTheRightOptionMoves() {
    Set<List<String>> orders = new HashSet<>();
    Set<Integer> rightPositions = new HashSet<>();
    for (long team = 1; team <= 50; team++) {
      List<String> order = SlotShuffle.order(IDS, team);
      orders.add(order);
      rightPositions.add(order.indexOf("B"));
    }
    assertTrue(orders.size() >= 10, "50 teams see many orders: " + orders.size());
    assertEquals(Set.of(0, 1, 2, 3), rightPositions, "the right option is not always at the same letter");
  }

  @Test
  void aLetterMapsBackToTheOptionShownAtThatPosition() {
    for (long team = 1; team <= 20; team++) {
      List<String> order = SlotShuffle.order(IDS, team);
      for (int i = 0; i < order.size(); i++) {
        assertEquals(Optional.of(order.get(i)), SlotShuffle.optionFor(IDS, team, SlotShuffle.letter(i)));
      }
      assertEquals(Optional.empty(), SlotShuffle.optionFor(IDS, team, "E"));
      assertEquals(Optional.empty(), SlotShuffle.optionFor(IDS, team, "b"));
    }
    assertEquals("A", SlotShuffle.letter(0));
    assertEquals("D", SlotShuffle.letter(3));
  }
}
