package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SwapRequest(@NotNull Long teamA, @NotNull Long teamB, @NotBlank @Size(min = 3, max = 500) String reason) {}
