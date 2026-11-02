package dev.devfest.event.dto;

import dev.devfest.event.domain.RoomActivity;

/** The admin's per-room activity setting; null removes the activity. */
public record RoomActivityRequest(RoomActivity activity) {}
