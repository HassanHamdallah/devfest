package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One experiment of a stage: the configuration to run. Repeating one is free. */
public record MissionRunRequest(@NotBlank @Size(max = 40) String config) {}
