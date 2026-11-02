package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Every admin override states why (3–500 characters); the reason is audited. */
public record OverrideReasonRequest(@NotBlank @Size(min = 3, max = 500) String reason) {}
