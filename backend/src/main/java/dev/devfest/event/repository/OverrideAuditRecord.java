package dev.devfest.event.repository;

import java.time.Instant;

/** One audited admin override with its reason. */
public record OverrideAuditRecord(Instant at, String by, String action, String target, String reason) {}
