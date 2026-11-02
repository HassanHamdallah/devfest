package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import java.nio.charset.StandardCharsets;

/** Byte-length guards: request DTOs limit characters, but the database CHECKs limit UTF-8 bytes. */
public final class EventText {

  private EventText() {}

  /** An override's reason, trimmed: 3–500 characters and at most 500 bytes (the audit column). */
  public static String requireReason(String reason) {
    String trimmed = reason == null ? "" : reason.trim();
    if (trimmed.length() < 3 || trimmed.length() > 500) throw fail(400, "Give a reason (3 to 500 characters).");
    requireMaxBytes(trimmed, 500, "Reason");
    return trimmed;
  }

  /** {@code field} is the user-facing field label, e.g. "Clue answer". */
  public static void requireMaxBytes(String value, int maxBytes, String field) {
    if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
      throw fail(400, field + " is too long (max " + maxBytes + " bytes).");
    }
  }
}
