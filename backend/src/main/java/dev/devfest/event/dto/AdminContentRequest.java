package dev.devfest.event.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code version} is the content version the editor loaded; {@code correctSuspectId} null clears the choice. */
public record AdminContentRequest(
  @NotNull @Size(max = 4000) String encryptedMessage,
  Long correctSuspectId,
  @NotNull @Min(1) Long version
) {}
