package dev.devfest.event.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Seconds added to the final's one timer, for every team (1 second to 60 minutes). */
public record FinalAddTimeRequest(@Min(1) @Max(3600) int seconds) {}
