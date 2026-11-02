package dev.devfest.event.dto;

import java.time.Instant;

/** One recent admin override on the board. */
public record OverrideAuditView(Instant at, String by, String action, String target, String reason) {}
