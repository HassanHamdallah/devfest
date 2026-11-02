package dev.devfest.event.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** The absolute round to switch to: 0 not started, 1..4 running, 5 rooms finished. */
public record RoundRequest(@NotNull @Min(0) @Max(5) Integer round) {}
