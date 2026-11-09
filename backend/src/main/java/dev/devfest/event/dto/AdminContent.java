package dev.devfest.event.dto;

import java.util.List;

/** The admin's content page: every room's content and settings; {@code readOnly} once the final has opened. */
public record AdminContent(List<RoomContentView> rooms, boolean readOnly) {}
