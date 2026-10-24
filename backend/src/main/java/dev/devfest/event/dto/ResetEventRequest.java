package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code confirmText} must be exactly "RESET"; {@code password} is the admin's own, checked server-side. */
public record ResetEventRequest(
  @NotBlank @Size(min = 3, max = 500) String reason,
  @NotNull @Size(max = 20) String confirmText,
  @NotBlank @Size(max = 72) String password
) {}
