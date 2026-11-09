package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerdictRequest(@NotBlank @Pattern(regexp = "YES|NO") String verdict) {}
