package dev.devfest.event.dto;

import java.util.List;

/**
 * What a terminal starts with: the room's files, paths relative to the home directory. Sent to the assigned
 * team only while its round is ASSIGNED, RUNNING or PAUSED (rules §18), and to the room's manager or ADMIN.
 */
public record TerminalStartFiles(List<File> files) {
  public record File(String path, String content) {}
}
