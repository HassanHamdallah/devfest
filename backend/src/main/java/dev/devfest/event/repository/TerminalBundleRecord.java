package dev.devfest.event.repository;

import java.time.Instant;

/**
 * One room_terminal_bundle row without its archive: the digest, counts and the lines the terminal appends to
 * /etc/passwd and /etc/group. The archive bytes are read only by {@link RoomTerminalBundleRepository#archive}.
 */
public record TerminalBundleRecord(
  long roomId,
  String sha256,
  int archiveBytes,
  int fileCount,
  long unpackedBytes,
  String owners,
  String etcPasswd,
  String etcGroup,
  String uploadedByName,
  Instant uploadedAt
) {}
