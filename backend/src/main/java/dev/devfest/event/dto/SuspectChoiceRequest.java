package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;

/** One of the six suspects, by the id the final screen listed (1 to 6); no free text. */
public record SuspectChoiceRequest(@NotNull Integer suspectId) {}
