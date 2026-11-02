package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** After round 1 starts, {@code confirmImpact} must be true: the admin has seen the rotation preview. */
public record MoveTeamRequest(
  @NotNull GroupCode group,
  @NotBlank @Size(min = 3, max = 500) String reason,
  Boolean confirmImpact
) {}
