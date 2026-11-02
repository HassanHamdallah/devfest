package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RoomExceptionRequest(
  @NotNull Long teamId,
  @NotNull Long roomId,
  @NotBlank @Size(min = 3, max = 500) String reason
) {}
