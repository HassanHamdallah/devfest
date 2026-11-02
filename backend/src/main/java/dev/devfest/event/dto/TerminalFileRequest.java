package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;

/** A terminal file to create or replace: path relative to the team's home, UTF-8 text content. */
public record TerminalFileRequest(@NotNull String path, @NotNull String content) {}
