package dev.devfest.event.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * A clue's passphrase check: surrounding whitespace (a copied line ending included) is removed, the rest is lower-cased
 * and its SHA-256 compared with the stored lower-case hex digest. Pure; the comparison takes the same time whatever the
 * input, and nothing about how close a wrong value was is ever computed.
 */
public final class Passphrase {

  private Passphrase() {}

  /** What is hashed: the typed value without surrounding whitespace, lower-cased. */
  public static String normalize(String typed) {
    return typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT);
  }

  /** The lower-case hex SHA-256 of the UTF-8 bytes of {@code text}. */
  public static String sha256Hex(String text) {
    return HexFormat.of().formatHex(digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  public static boolean matches(String typed, String expectedSha256Hex) {
    if (expectedSha256Hex == null) return false;
    byte[] actual = sha256Hex(normalize(typed)).getBytes(StandardCharsets.US_ASCII);
    byte[] expected = expectedSha256Hex.getBytes(StandardCharsets.US_ASCII);
    return MessageDigest.isEqual(actual, expected);
  }

  private static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
