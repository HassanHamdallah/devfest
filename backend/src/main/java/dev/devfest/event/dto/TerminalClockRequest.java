package dev.devfest.event.dto;

import java.time.Instant;

/** The terminal clock of a room's server package as a UTC instant such as 2026-03-14T09:26:00Z; null = real time. */
public record TerminalClockRequest(Instant clock) {}
