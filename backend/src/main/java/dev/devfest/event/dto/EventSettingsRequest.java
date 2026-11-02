package dev.devfest.event.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record EventSettingsRequest(@NotNull @Min(1) @Max(180) Integer roundMinutes) {}
