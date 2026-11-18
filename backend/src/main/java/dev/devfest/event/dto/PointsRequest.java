package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Points to add (positive) or subtract (negative): a whole number from -100 to 100, not 0. Read as a decimal so 2.5 is refused. */
public record PointsRequest(@NotNull BigDecimal delta) {}
