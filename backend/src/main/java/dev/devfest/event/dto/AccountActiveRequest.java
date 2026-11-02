package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;

public record AccountActiveRequest(@NotNull Boolean active) {}
