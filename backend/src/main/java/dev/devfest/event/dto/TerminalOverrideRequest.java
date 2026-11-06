package dev.devfest.event.dto;

/** An organizer's decision on one item: true awards its points, false denies them, null goes back to automatic. */
public record TerminalOverrideRequest(Boolean awarded) {}
