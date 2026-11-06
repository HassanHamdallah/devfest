package dev.devfest.event.dto;

import dev.devfest.event.domain.RoomActivity;

public record RoomActivityView(long roomId, RoomActivity activity) {}
