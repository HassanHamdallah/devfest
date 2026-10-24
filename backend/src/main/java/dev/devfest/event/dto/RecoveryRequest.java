package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecoveryRequest(@NotBlank @Size(max = 500) String answer) {}
