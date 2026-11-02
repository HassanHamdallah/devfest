package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AccusationRequest(
  @NotNull Long suspectId,
  @NotBlank @Size(max = 4000) String decryptedMessage,
  @NotBlank @Size(max = 4000) String evidence
) {}
