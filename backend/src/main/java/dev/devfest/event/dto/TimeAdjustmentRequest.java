package dev.devfest.event.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Signed minutes added to (positive) or taken from (negative) the team's adjusted time; never 0. */
public record TimeAdjustmentRequest(
  @NotNull Long teamId,
  @NotNull @Min(-60) @Max(60) Integer minutes,
  @NotBlank @Size(min = 3, max = 500) String reason
) {}
