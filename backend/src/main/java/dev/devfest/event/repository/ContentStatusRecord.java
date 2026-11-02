package dev.devfest.event.repository;

/** Completeness of the final's shared content (room content is reported per room). */
public record ContentStatusRecord(boolean hasEncryptedMessage, boolean hasCorrectSuspect, int suspectCount) {}
