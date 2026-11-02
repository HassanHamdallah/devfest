package dev.devfest.event.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code version} is the room content version the editor loaded. */
public record RoomContentRequest(
  @NotNull @Size(max = 500) String clueKey,
  @NotNull @Size(max = 4000) String recoveryPrompt,
  @NotNull @Size(max = 500) String recoveryAnswer,
  @NotNull @Min(1) Long version
) {}
