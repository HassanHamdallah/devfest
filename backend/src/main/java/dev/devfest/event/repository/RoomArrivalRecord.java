package dev.devfest.event.repository;

import java.time.Instant;

/** A room manager's "All teams are here" for one room in one round. */
public record RoomArrivalRecord(int round, long roomId, long confirmedBy, String confirmedByName, Instant confirmedAt) {}
