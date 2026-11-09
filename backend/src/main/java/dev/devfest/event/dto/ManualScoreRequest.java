package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** The manager-typed score (0 to 15, at most 2 decimals) of a team in a room that has no automatic score. */
public record ManualScoreRequest(@NotNull BigDecimal score) {}
