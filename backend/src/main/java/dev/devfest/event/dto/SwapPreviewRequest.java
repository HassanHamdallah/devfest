package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;

public record SwapPreviewRequest(@NotNull Long teamA, @NotNull Long teamB) {}
