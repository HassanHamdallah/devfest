package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;

public record ArrivalRequest(@NotNull Boolean ready) {}
