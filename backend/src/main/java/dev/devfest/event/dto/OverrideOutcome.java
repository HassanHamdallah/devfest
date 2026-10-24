package dev.devfest.event.dto;

import java.util.List;

/** The result of an override: what happened, non-blocking warnings, and the snapshot a reset saved. */
public record OverrideOutcome(String message, List<String> warnings, Long snapshotId) {

  public static OverrideOutcome done(String message) {
    return new OverrideOutcome(message, List.of(), null);
  }
}
