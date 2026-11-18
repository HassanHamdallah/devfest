package dev.devfest.event.dto;

import java.time.Instant;

/** One ledger row for the history (newest first). {@code undo}: it reverses an earlier row; {@code undone}: a later undo reversed it. */
public record PointsHistoryEntry(long id, boolean undo, int delta, int totalAfter, String enteredBy, Instant enteredAt, boolean undone) {}
