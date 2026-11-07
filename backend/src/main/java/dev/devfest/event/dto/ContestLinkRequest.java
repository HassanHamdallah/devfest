package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The room's contest link: one https URL (checked by ContestUrl). */
public record ContestLinkRequest(@NotNull @Size(max = 2000) String url) {}
