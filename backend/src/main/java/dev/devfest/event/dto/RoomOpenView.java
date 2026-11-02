package dev.devfest.event.dto;

import dev.devfest.event.domain.RoomCode;

/** A room's open flag after an open or close request. */
public record RoomOpenView(long roomId, RoomCode code, boolean open) {}
