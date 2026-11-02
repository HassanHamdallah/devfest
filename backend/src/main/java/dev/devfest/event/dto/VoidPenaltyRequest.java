package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VoidPenaltyRequest(@NotBlank @Size(max = 500) String reason) {}
