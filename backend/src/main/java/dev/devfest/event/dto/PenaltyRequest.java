package dev.devfest.event.dto;

import dev.devfest.event.domain.PenaltyKind;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PenaltyRequest(
  @NotNull Long teamId,
  @NotNull PenaltyKind kind,
  @NotNull @Size(max = 500) String note
) {}
