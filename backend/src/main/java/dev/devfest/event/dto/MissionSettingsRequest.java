package dev.devfest.event.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** The total a team needs for the room's clue (0-100); locked once a team of the room was evaluated. */
public record MissionSettingsRequest(@NotNull @Min(0) @Max(100) Integer passScore) {}
