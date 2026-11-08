package dev.devfest.event.dto;

import java.time.Instant;

/** What a team gets for a submitted stage: never points and never whether it was right. */
public record MissionSavedReceipt(int stage, String message, Instant savedAt) {
  public static final String SAVED = "Saved";
}
