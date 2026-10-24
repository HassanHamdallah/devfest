package dev.devfest.event.dto;

import dev.devfest.event.domain.RoomCode;
import java.time.Instant;
import java.util.UUID;

/** Proof that a room submission was stored; deliberately without the answer or the clue key. */
public record Receipt(UUID id, RoomCode roomCode, Instant receivedAt, boolean clueCorrect) {}
