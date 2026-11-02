package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import java.nio.charset.StandardCharsets;

/** Byte-length guards: request DTOs limit characters, but the database CHECKs limit UTF-8 bytes. */
public final class EventText {

  private EventText() {}

  /** {@code field} is the user-facing field label, e.g. "Clue answer". */
  public static void requireMaxBytes(String value, int maxBytes, String field) {
    if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
      throw fail(400, field + " is too long (max " + maxBytes + " bytes).");
    }
  }
}
