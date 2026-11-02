package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;

public record TerminalSettingRequest(@NotNull Boolean enabled) {}
