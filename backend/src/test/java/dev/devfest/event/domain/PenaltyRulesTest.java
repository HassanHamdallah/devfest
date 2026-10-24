package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PenaltyRulesTest {

  private record Penalty(PenaltyKind kind, int minutes, boolean voided) implements PenaltyRules.CountedPenalty {
    static Penalty active(PenaltyKind kind) {
      return new Penalty(kind, kind.minutes(), false);
    }

    static Penalty voided(PenaltyKind kind) {
      return new Penalty(kind, kind.minutes(), true);
    }
  }

  @Test
  void kindsCarryTheContractMinutesAndEntryRules() {
    assertEquals(2, PenaltyKind.HINT.minutes());
    assertEquals(2, PenaltyKind.MINOR_VIOLATION.minutes());
    assertEquals(5, PenaltyKind.PROHIBITED_TOOL.minutes());
    assertEquals(5, PenaltyKind.CHEATING.minutes());
    assertEquals(5, PenaltyKind.ANSWER_SHARING.minutes());
    assertEquals(5, PenaltyKind.RECOVERY.minutes());
    assertEquals(3, PenaltyKind.WRONG_ACCUSATION.minutes());
    assertFalse(PenaltyKind.RECOVERY.managerEntered());
    assertFalse(PenaltyKind.WRONG_ACCUSATION.managerEntered());
  }

  @Test
  void automaticKindsCannotBeEnteredByHand() {
    var recovery = assertThrows(EventRuleException.class, () -> PenaltyRules.requireManagerEntered(PenaltyKind.RECOVERY));
    assertEquals("This penalty kind is added automatically.", recovery.getMessage());
    assertThrows(EventRuleException.class, () -> PenaltyRules.requireManagerEntered(PenaltyKind.WRONG_ACCUSATION));
    for (PenaltyKind kind : List.of(
      PenaltyKind.HINT,
      PenaltyKind.MINOR_VIOLATION,
      PenaltyKind.PROHIBITED_TOOL,
      PenaltyKind.CHEATING,
      PenaltyKind.ANSWER_SHARING
    )) {
      assertDoesNotThrow(() -> PenaltyRules.requireManagerEntered(kind));
    }
  }

  @Test
  void voidedPenaltiesNeverCount() {
    var penalties = List.of(
      Penalty.active(PenaltyKind.HINT),
      Penalty.voided(PenaltyKind.CHEATING),
      Penalty.active(PenaltyKind.WRONG_ACCUSATION),
      Penalty.active(PenaltyKind.HINT)
    );
    assertEquals(7, PenaltyRules.activeMinutes(penalties));
    assertEquals(2, PenaltyRules.activeCount(penalties, PenaltyKind.HINT));
    assertEquals(0, PenaltyRules.activeCount(penalties, PenaltyKind.CHEATING));
    assertEquals(0, PenaltyRules.activeMinutes(List.of()));
  }

  @Test
  void hintCounterIsComparedWithActiveHintPenalties() {
    var penalties = List.of(Penalty.active(PenaltyKind.HINT), Penalty.voided(PenaltyKind.HINT));
    assertFalse(PenaltyRules.hintPenaltyMismatch(1, penalties));
    assertTrue(PenaltyRules.hintPenaltyMismatch(2, penalties));
    assertTrue(PenaltyRules.hintPenaltyMismatch(0, penalties));
    assertFalse(PenaltyRules.hintPenaltyMismatch(0, List.of()));
  }
}
