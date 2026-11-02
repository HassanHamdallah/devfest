package dev.devfest.event.repository;

import java.time.Instant;
import java.util.UUID;

/** A team that solved the case, with the name of the suspect its finishing accusation named. */
public record TeamFinishRecord(long teamId, Instant finishedAt, UUID finishingAccusationId, String suspectName) {}
