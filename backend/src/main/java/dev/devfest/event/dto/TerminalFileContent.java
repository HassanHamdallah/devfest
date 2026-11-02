package dev.devfest.event.dto;

/** One terminal file with its content, for the manager's editor. */
public record TerminalFileContent(long id, String path, String content, int sizeBytes) {}
