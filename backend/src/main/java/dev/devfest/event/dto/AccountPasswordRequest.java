package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountPasswordRequest(@NotBlank @Size(min = 12, max = 72) String password) {}
