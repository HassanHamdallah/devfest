package dev.devfest.event.dto;

/** {@code managerId} null removes the room's manager. */
public record RoomManagerRequest(Long managerId) {}
