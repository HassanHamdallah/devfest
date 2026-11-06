package dev.devfest.event.repository;

/** A package's digest and archive read by one statement, so the two always belong together. */
public record TerminalBundleBytes(String sha256, byte[] archive) {}
