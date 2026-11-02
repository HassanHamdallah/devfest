package dev.devfest.event.dto;

import dev.devfest.event.domain.JudgeStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code version} is the judgement version the manager saw (0 when none existed). */
public record JudgementRequest(
  @NotNull JudgeStatus status,
  @NotNull @Min(0) @Max(50) Integer hints,
  @NotNull @Size(max = 1000) String note,
  @NotNull @Min(0) Long version
) {}
