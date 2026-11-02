package dev.devfest.event.repository;

import dev.devfest.event.domain.RoomCode;

/** A room with its manager's display name, for organization screens. */
public record RoomManagerRecord(long id, RoomCode code, String name, Long managerId, String managerName) {}
