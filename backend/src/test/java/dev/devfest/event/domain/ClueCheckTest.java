package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClueCheckTest {

  @Test
  void answersAreComparedAfterNormalization() {
    assertTrue(ClueCheck.isCorrect("  white   van ", "WHITE VAN"));
    assertFalse(ClueCheck.isCorrect("WHITE-VAN", "WHITE VAN"));
  }

  @Test
  void aBlankKeyIsNeverCorrect() {
    assertFalse(ClueCheck.isCorrect("", ""));
    assertFalse(ClueCheck.isCorrect("   ", " \t "));
    assertFalse(ClueCheck.isCorrect("anything", null));
    assertFalse(ClueCheck.hasKey("  \n "));
    assertFalse(ClueCheck.hasKey(null));
    assertTrue(ClueCheck.hasKey(" x "));
  }

  @Test
  void onlyAChangeThatAcceptsOtherAnswersCountsAsAKeyChange() {
    assertFalse(ClueCheck.keyChanged("WHITE VAN", "  white  van "));
    assertTrue(ClueCheck.keyChanged("WHITE VN", "WHITE VAN"));
    assertTrue(ClueCheck.keyChanged("", "WHITE VAN"));
  }
}
