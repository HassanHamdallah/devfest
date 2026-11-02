package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;

public record PublicationRequest(@NotNull Boolean published) {}
