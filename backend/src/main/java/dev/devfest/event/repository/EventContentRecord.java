package dev.devfest.event.repository;

import java.time.Instant;

/** The single event_content row; {@code correctSuspectId} is never exposed to teams. */
public record EventContentRecord(String encryptedMessage, Long correctSuspectId, long version, Instant updatedAt) {}
