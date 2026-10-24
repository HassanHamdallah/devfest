package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code accountId} is the team's MEMBER account. */
public record CreateTeamRequest(
  @NotBlank @Size(max = 80) String name,
  @NotNull GroupCode groupCode,
  @NotNull Long accountId
) {}
