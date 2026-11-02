package dev.devfest.event.dto;

import dev.devfest.event.domain.RoomCode;

/** A room with its manager; both manager fields are null when none is assigned. */
public record RoomManagerView(long id, RoomCode code, String name, Long managerId, String managerName) {}
