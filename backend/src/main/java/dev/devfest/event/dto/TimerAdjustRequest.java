package dev.devfest.event.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Positive adds time, negative removes it; never 0. */
public record TimerAdjustRequest(@NotNull @Min(-3600) @Max(3600) Integer deltaSeconds) {}
