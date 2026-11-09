package dev.devfest.event.dto;

import dev.devfest.event.domain.FinalScreen;
import dev.devfest.event.domain.FinalStage;

/**
 * The answer to a final step. {@code accepted}: the step succeeded (a right unlock or layer, a recorded verdict or
 * suspect). A wrong unlock or layer answer is a normal answer with the generic {@code message}; the response never says
 * which part was wrong and never carries the value. {@code waitSeconds}: how long the team must wait before trying again.
 */
public record FinalActionResult(boolean accepted, String message, int waitSeconds, FinalStage status, FinalScreen screen) {}
