package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** "Cheating (-7.5)": needs the organizer's confirmation and a reason. */
public record CheatingRequest(
  @NotNull Long teamId,
  @NotBlank @Size(max = 500) String reason,
  @NotNull Boolean confirmed
) {}
