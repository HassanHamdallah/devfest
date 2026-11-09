package dev.devfest.event.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code version} is the room content version the editor loaded. */
public record RoomContentRequest(@NotNull @Size(max = 500) String clueKey, @NotNull @Min(1) Long version) {}
