package dev.devfest.event.dto;

import dev.devfest.event.domain.ClueOverride;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** {@code version} is the verdict version the manager's board showed. */
public record ClueOverrideRequest(@NotNull ClueOverride override, @NotNull @Min(1) Long version) {}
