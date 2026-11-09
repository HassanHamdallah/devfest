package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** The four room outputs, in room order (AI, Problem solving, Linux, Hardware). */
public record UnlockRequest(@NotNull @Size(min = 4, max = 4) List<@Size(max = 500) String> codes) {}
