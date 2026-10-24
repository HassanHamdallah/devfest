package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClueVerdictTest {

  @Test
  void anOverrideWinsOverTheAutomaticResult() {
    assertTrue(ClueVerdict.effective(false, ClueOverride.ACCEPT));
    assertTrue(ClueVerdict.effective(true, ClueOverride.ACCEPT));
    assertFalse(ClueVerdict.effective(true, ClueOverride.REJECT));
    assertFalse(ClueVerdict.effective(false, ClueOverride.REJECT));
  }

  @Test
  void automaticFollowsTheKey() {
    assertTrue(ClueVerdict.effective(true, ClueOverride.AUTOMATIC));
    assertFalse(ClueVerdict.effective(false, ClueOverride.AUTOMATIC));
  }

  @Test
  void automaticIsStoredAsNull() {
    assertNull(ClueOverride.AUTOMATIC.stored());
    assertEquals("ACCEPT", ClueOverride.ACCEPT.stored());
    assertEquals(ClueOverride.AUTOMATIC, ClueOverride.fromStored(null));
    assertEquals(ClueOverride.REJECT, ClueOverride.fromStored("REJECT"));
  }
}
