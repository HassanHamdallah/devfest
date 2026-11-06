package dev.devfest.event.dto;

import java.time.Instant;

/** What a team gets for a saved answer or repair: never whether it was correct. */
public record QuizSavedReceipt(long itemId, String message, Instant savedAt) {
  public static final String SAVED = "Response saved";
}
