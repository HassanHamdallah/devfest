package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerminalBundleEtagTest {

  private static final String SHA = "ab".repeat(32);

  @Test
  void theEtagIsTheQuotedDigest() {
    assertEquals("\"" + SHA + "\"", TerminalBundleEtag.of(SHA));
  }

  @Test
  void matchesStrongWeakListedAndWildcardValidators() {
    assertTrue(TerminalBundleEtag.matches("\"" + SHA + "\"", SHA));
    assertTrue(TerminalBundleEtag.matches("W/\"" + SHA + "\"", SHA));
    assertTrue(TerminalBundleEtag.matches("\"other\", \"" + SHA + "\"", SHA));
    assertTrue(TerminalBundleEtag.matches("*", SHA));
  }

  @Test
  void anythingElseDoesNotMatch() {
    assertFalse(TerminalBundleEtag.matches(null, SHA));
    assertFalse(TerminalBundleEtag.matches("", SHA));
    assertFalse(TerminalBundleEtag.matches("\"" + "cd".repeat(32) + "\"", SHA));
    assertFalse(TerminalBundleEtag.matches("\"" + SHA.substring(1) + "\"", SHA));
  }
}
