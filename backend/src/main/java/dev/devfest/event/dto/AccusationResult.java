package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalStage;
import java.time.Instant;
import java.util.UUID;

/** A wrong result never says which suspect would have been right. */
public record AccusationResult(UUID receiptId, boolean correct, FinalStage stage, Instant receivedAt) {}
