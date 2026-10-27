package dev.devfest.event.repository;

/**
 * Published by {@link EventStateRepository#bumpRevision()} inside the writing transaction. The push to open
 * screens listens for it after commit, so a rolled-back write is never announced.
 */
public record RevisionBumped(long revision) {}
