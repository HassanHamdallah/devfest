package dev.devfest.event.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The multiple-choice cutoff in running minutes of the round, and the total a team needs for the room's clue
 * (0–100; null keeps the current pass score, which is locked once a team was evaluated).
 */
public record QuizSettingsRequest(@NotNull @Min(1) @Max(180) Integer mcqMinutes, @Min(0) @Max(100) Integer passScore) {}
