package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** "Hint given (-2)": one click; the note is optional. */
public record HintRequest(@NotNull Long teamId, @Size(max = 500) String note) {}
