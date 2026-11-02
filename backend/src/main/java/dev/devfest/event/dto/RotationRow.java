package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.List;

/** A group's rooms in rounds 1..4 (list index 0..3). */
public record RotationRow(GroupCode groupCode, List<RoomCode> rooms) {}
