package dev.devfest.event.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PlannedTeamsRequest(@NotNull @Min(1) @Max(64) Integer plannedCount) {}
