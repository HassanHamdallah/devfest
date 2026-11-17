package dev.devfest.event.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** A clue's passphrase: trimmed, lower-cased, SHA-256, compared with the stored hex. */
class PassphraseTest {

  private static final String SECRET = "3f1c9a0b7d2e4f6a8b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f60718293a4b5c6";
  private static final String HASH = Passphrase.sha256Hex(SECRET);

  @Test
  void theHashIsLowerCaseHexOfTheExactBytes() {
    assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Passphrase.sha256Hex(""));
    assertEquals(64, HASH.length());
    assertEquals(HASH, HASH.toLowerCase(Locale.ROOT));
  }

  @Test
  void theExactSecretMatches() {
    assertTrue(Passphrase.matches(SECRET, HASH));
  }

  @Test
  void aTrailingLineEndingOrSurroundingSpacesStillMatch() {
    assertTrue(Passphrase.matches(SECRET + "\n", HASH));
    assertTrue(Passphrase.matches(SECRET + "\r\n", HASH));
    assertTrue(Passphrase.matches("  \t" + SECRET + "  ", HASH));
  }

  @Test
  void upperCaseStillMatches() {
    assertTrue(Passphrase.matches(SECRET.toUpperCase(Locale.ROOT), HASH));
    assertTrue(Passphrase.matches(" " + SECRET.toUpperCase(Locale.ROOT) + "\r\n", HASH));
  }

  @Test
  void anythingElseDoesNotMatch() {
    assertFalse(Passphrase.matches(SECRET.substring(1), HASH));
    assertFalse(Passphrase.matches(SECRET.substring(0, 32) + " " + SECRET.substring(32), HASH), "inner whitespace counts");
    assertFalse(Passphrase.matches("", HASH));
    assertFalse(Passphrase.matches(null, HASH));
    assertFalse(Passphrase.matches(SECRET, null));
    assertFalse(Passphrase.matches(SECRET, HASH.toUpperCase(Locale.ROOT)), "the stored hash is lower-case hex");
  }
}
