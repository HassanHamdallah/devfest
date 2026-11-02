package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SuspectRequest(@NotBlank @Size(max = 80) String name, @NotNull @Size(max = 300) String description) {}
