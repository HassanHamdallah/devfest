package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;

public record RoomExceptionPreviewRequest(@NotNull Long teamId, @NotNull Long roomId) {}
