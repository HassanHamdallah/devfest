package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One of the listed suspects, by the id the final screen listed ("S11"); no free text. */
public record SuspectChoiceRequest(@NotBlank @Size(max = 10) String suspectId) {}
