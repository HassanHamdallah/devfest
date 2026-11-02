package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalStage;
import java.time.Instant;
import java.util.UUID;

public record RecoveryResult(UUID attemptId, boolean correct, FinalStage stage, Instant receivedAt) {}
