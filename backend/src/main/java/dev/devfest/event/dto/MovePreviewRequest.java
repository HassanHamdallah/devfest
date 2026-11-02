package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import jakarta.validation.constraints.NotNull;

public record MovePreviewRequest(@NotNull Long teamId, @NotNull GroupCode group) {}
