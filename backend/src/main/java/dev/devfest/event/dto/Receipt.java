package dev.devfest.event.dto;

import dev.devfest.event.domain.RoomCode;
import java.time.Instant;
import java.util.UUID;

/**
 * Proof that a room submission was stored; deliberately without the answer or the clue key. {@code clueCorrect}
 * is the current (effective) result; {@code updatedAfterReview} is true when it differs from the result at
 * submission time. Whether a key change or a manager's override caused it is never revealed.
 */
public record Receipt(UUID id, RoomCode roomCode, Instant receivedAt, boolean clueCorrect, boolean updatedAfterReview) {}
