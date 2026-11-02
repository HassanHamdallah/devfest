package dev.devfest.event.repository;

/** One suspect of the final. */
public record SuspectRecord(long id, String name, String description, int position) {}
