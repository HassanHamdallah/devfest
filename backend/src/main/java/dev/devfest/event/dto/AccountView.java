package dev.devfest.event.dto;

/** An event account; {@code teamId} is set for a linked MEMBER, {@code roomId} for an assigned MANAGER. */
public record AccountView(
  long id,
  String email,
  String name,
  EventAccountRole role,
  boolean active,
  Long teamId,
  Long roomId
) {}
