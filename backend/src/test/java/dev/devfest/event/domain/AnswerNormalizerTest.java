package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class AnswerNormalizerTest {

  @Test
  void trimsCollapsesWhitespaceAndUppercases() {
    assertEquals("WHITE VAN", AnswerNormalizer.normalize("  white \t\n  van "));
    assertEquals("WHITE VAN", AnswerNormalizer.normalize("white  van"));
    assertEquals("", AnswerNormalizer.normalize(" \t "));
    assertEquals("", AnswerNormalizer.normalize(null));
  }

  @Test
  void uppercasingIgnoresTheDefaultLocale() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertEquals("TITLE", AnswerNormalizer.normalize("title"));
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void matchesForgivingWhitespaceAndCase() {
    assertTrue(AnswerNormalizer.matches("  white   van ", "WHITE VAN"));
    assertTrue(AnswerNormalizer.matches("pwd", "pwd"));
    assertTrue(AnswerNormalizer.matches("Four", "FOUR"));
  }

  @Test
  void keepsPunctuationSignificant() {
    assertFalse(AnswerNormalizer.matches("WHITE-VAN", "WHITE VAN"));
    assertTrue(AnswerNormalizer.matches("+970-59-881", "+970-59-881"));
    assertFalse(AnswerNormalizer.matches("970-59-881", "+970-59-881"));
    assertFalse(AnswerNormalizer.matches("+97059881", "+970-59-881"));
  }

  @Test
  void emptyKeyNeverMatches() {
    assertFalse(AnswerNormalizer.matches("", ""));
    assertFalse(AnswerNormalizer.matches("  ", " "));
    assertFalse(AnswerNormalizer.matches("anything", null));
  }
}
