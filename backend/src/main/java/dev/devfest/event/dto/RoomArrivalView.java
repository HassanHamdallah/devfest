package dev.devfest.event.dto;

import java.time.Instant;

/** "All teams are here" for one room in the current round; {@code by} and {@code at} are null while waiting. */
public record RoomArrivalView(boolean ready, String by, Instant at) {

  public static final RoomArrivalView WAITING = new RoomArrivalView(false, null, null);
}
